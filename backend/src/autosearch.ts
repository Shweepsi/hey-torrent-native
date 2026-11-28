/**
 * Tâches planifiées du Worker (déclencheur `cron`, toutes les heures) :
 *
 * - recherche automatique : chaque demande en attente sans release convenable
 *   est cherchée sur les sources de l'administrateur au plus toutes les 6 h ;
 *   Claude juge les résultats (un appel par recherche, et aucun si les noms
 *   trouvés n'ont pas changé depuis un « aucune ne convient ») ;
 * - alerte : une demande sans release convenable depuis 3 semaines prévient
 *   l'administrateur, une fois ;
 * - Plex : la bibliothèque est relue toutes les 6 h si une connexion est réglée.
 *
 * Rien n'est journalisé qui contienne une adresse, une clé ou un nom de release.
 */
import { adminIds } from "./accounts";
import { automationEnabled, loadSettings, type WorkerSettings } from "./adminsettings";
import type { FetchLike } from "./apple";
import { devicesOfUsers, pushTo } from "./availability";
import { MAX_CANDIDATES, recommend, type Candidate, type Wanted } from "./claude";
import { sha256Hex } from "./crypto";
import type { Env } from "./env";
import { ApiError } from "./http";
import { syncPlex } from "./plex";
import type { PushSender } from "./push";
import { cachedTmdb } from "./tmdb";
import { containsWord, searchSource, type FeedItem, type Mode, type SourceFailure } from "./torznab";
import type { MediaType } from "./watchlist";

/** Intervalle entre deux recherches d'une même demande. */
export const SEARCH_INTERVAL = 6 * 3600;
/** Intervalle entre deux lectures de la bibliothèque Plex. */
export const PLEX_INTERVAL = 6 * 3600;
/** Attente sans release convenable avant l'alerte à l'administrateur. */
export const STALE_AFTER = 21 * 86_400;
/** Demandes cherchées au plus par passage (limite d'appels sortants du Worker). */
export const MAX_PER_RUN = 8;

/**
 * Colonnes remises à zéro quand une demande repart en attente (réouverture,
 * saison manquante) ; `?1` est l'heure courante.
 */
export const RESET_SEARCH = `candidate_title = NULL, candidate_reason = NULL, candidate_found_at = NULL,
  auto_checked_at = NULL, auto_fingerprint = NULL, waiting_since = ?1, stale_notified_at = NULL`;

interface DueRequest {
  id: string;
  media_type: MediaType;
  tmdb_id: number;
  title: string;
  year: number | null;
  auto_fingerprint: string | null;
}

/** Fiche TMDB du titre (cache du Worker) : titre original et date de sortie. */
async function tmdbFacts(env: Env, fetcher: FetchLike, request: DueRequest, now: number) {
  try {
    const raw: unknown = JSON.parse(await cachedTmdb(env, fetcher, `${request.media_type}/${request.tmdb_id}`, new URLSearchParams(), now));
    const root = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
    const original = root.original_title ?? root.original_name;
    const date = typeof root.release_date === "string" ? Date.parse(root.release_date) : NaN;
    return {
      originalTitle: typeof original === "string" && original.trim() ? original.trim() : undefined,
      releasedAt: Number.isFinite(date) ? Math.floor(date / 1000) : undefined,
    };
  } catch {
    return { originalTitle: undefined, releasedAt: undefined };
  }
}

/** Série : la saison à chercher quand tous les suiveurs veulent la même, seule. */
async function singleSeason(env: Env, requestId: string): Promise<number | undefined> {
  const { results } = await env.DB.prepare("SELECT seasons FROM watchlist_items WHERE request_id = ? AND deleted_at IS NULL")
    .bind(requestId)
    .all<{ seasons: string | null }>();
  const union = new Set<number>();
  for (const { seasons } of results) {
    if (seasons === null) return undefined;
    try {
      const list: unknown = JSON.parse(seasons);
      if (!Array.isArray(list)) return undefined;
      for (const s of list) if (typeof s === "number") union.add(s);
    } catch {
      return undefined;
    }
  }
  return union.size === 1 ? [...union][0] : undefined;
}

/** Candidats envoyés à Claude : sans source morte ni terme exclu, sans doublon, les mieux partagés d'abord. */
export function shortlist(items: FeedItem[], excludedTerms: string[]): Candidate[] {
  const seen = new Set<string>();
  return items
    .filter((item) => item.seeders === undefined || item.seeders > 0)
    .filter((item) => !excludedTerms.some((term) => containsWord(item.title, term)))
    .filter((item) => {
      const key = item.title.toLowerCase();
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((a, b) => (b.seeders ?? 0) - (a.seeders ?? 0))
    .slice(0, MAX_CANDIDATES)
    .map((item, index) => ({
      id: `c${index}`,
      title: item.title,
      size_bytes: item.size_bytes,
      seeders: item.seeders,
      indexer: item.indexer,
    }));
}

type AssistantStatus = "ok" | "auth_failed" | "unavailable";

interface SearchRun {
  reports: Map<string, SourceFailure | null>;
  assistant: AssistantStatus | null;
}

/** Cherche une demande ; rend faux si Claude est injoignable (le passage s'arrête). */
async function searchOne(env: Env, fetcher: FetchLike, settings: WorkerSettings, request: DueRequest, run: SearchRun, now: number): Promise<boolean> {
  const facts = await tmdbFacts(env, fetcher, request, now);
  const checked = (fingerprint: string | null) =>
    env.DB.prepare("UPDATE requests SET auto_checked_at = ?, auto_fingerprint = ? WHERE id = ?").bind(now, fingerprint, request.id).run();
  // Film pas encore sorti : rien à chercher avant sa date.
  if (request.media_type === "movie" && facts.releasedAt !== undefined && facts.releasedAt > now) {
    await checked(request.auto_fingerprint);
    return true;
  }
  const season = request.media_type === "tv" ? await singleSeason(env, request.id) : undefined;
  const mode: Mode = request.media_type === "movie" ? { kind: "movie" } : { kind: "tv", season };
  const texts = [request.title];
  if (facts.originalTitle && facts.originalTitle.toLowerCase() !== request.title.toLowerCase()) texts.push(facts.originalTitle);
  const results = await Promise.all(settings.sources.filter((s) => s.enabled).map((source) => searchSource(fetcher, source, mode, texts)));
  const items: FeedItem[] = [];
  for (const result of results) {
    // Une source qui répond une fois efface son échec du passage.
    if (result.failure === null || !run.reports.has(result.source.id)) run.reports.set(result.source.id, result.failure);
    items.push(...result.items);
  }
  const candidates = shortlist(items, settings.excludedTerms);
  if (candidates.length === 0) {
    await checked(null);
    return true;
  }
  const fingerprint = await sha256Hex(candidates.map((c) => c.title).sort().join("\n"));
  if (fingerprint === request.auto_fingerprint) {
    await checked(fingerprint);
    return true;
  }
  const wanted: Wanted = { media_type: request.media_type, title: request.title, original_title: facts.originalTitle, year: request.year ?? undefined, season };
  let choice;
  try {
    choice = await recommend(settings.anthropicKey ?? "", settings.rules, wanted, candidates, fetcher);
  } catch (error) {
    run.assistant = error instanceof ApiError && error.code === "assistant_auth_failed" ? "auth_failed" : "unavailable";
    return false;
  }
  run.assistant = "ok";
  const chosen = candidates.find((c) => c.id === choice.release_id);
  if (!chosen) {
    await checked(fingerprint);
    return true;
  }
  await env.DB.prepare(
    `UPDATE requests SET candidate_title = ?1, candidate_reason = ?2, candidate_found_at = ?3, auto_checked_at = ?3, auto_fingerprint = ?4
     WHERE id = ?5 AND status = 'requested'`,
  )
    .bind(chosen.title, choice.reason, now, fingerprint, request.id)
    .run();
  return true;
}

async function searchDue(env: Env, fetcher: FetchLike, settings: WorkerSettings, now: number): Promise<void> {
  const { results } = await env.DB.prepare(
    `SELECT id, media_type, tmdb_id, title, year, auto_fingerprint FROM requests
     WHERE status = 'requested' AND candidate_title IS NULL AND (auto_checked_at IS NULL OR auto_checked_at <= ?1)
     ORDER BY auto_checked_at IS NOT NULL, auto_checked_at, created_at LIMIT ${MAX_PER_RUN}`,
  )
    .bind(now - SEARCH_INTERVAL)
    .all<DueRequest>();
  if (results.length === 0) return;
  const run: SearchRun = { reports: new Map(), assistant: null };
  for (const request of results) {
    if (!(await searchOne(env, fetcher, settings, request, run, now))) break;
  }
  const reports = [...run.reports].map(([id, failure]) => ({ id, failure }));
  await env.DB.prepare(
    "UPDATE automation SET searched_at = ?1, source_reports = ?2, assistant_status = COALESCE(?3, assistant_status) WHERE id = 1",
  )
    .bind(now, JSON.stringify(reports), run.assistant)
    .run();
}

/** Prévient l'administrateur, une fois, des demandes qui attendent depuis trop longtemps. */
async function alertStale(env: Env, sender: PushSender, now: number): Promise<void> {
  const { results } = await env.DB.prepare(
    `SELECT id FROM requests WHERE status = 'requested' AND candidate_title IS NULL
       AND stale_notified_at IS NULL AND COALESCE(waiting_since, created_at) <= ?`,
  )
    .bind(now - STALE_AFTER)
    .all<{ id: string }>();
  if (results.length === 0) return;
  const ids = results.map((r) => r.id);
  for (let start = 0; start < ids.length; start += 90) {
    const chunk = ids.slice(start, start + 90);
    await env.DB.prepare(`UPDATE requests SET stale_notified_at = ? WHERE id IN (${chunk.map(() => "?").join(", ")})`)
      .bind(now, ...chunk)
      .run();
  }
  const devices = await devicesOfUsers(env, await adminIds(env), now);
  await pushTo(env, sender, devices, {
    title: "HeyWatchlist",
    bodyKey: "push_requests_stale",
    bodyArgs: [String(ids.length)],
    collapseKey: "requests-stale",
    mediaType: "movie",
    tmdbId: 0,
  });
}

async function plexDue(env: Env, fetcher: FetchLike, sender: PushSender, settings: WorkerSettings, now: number): Promise<void> {
  if (!settings.plex) return;
  const row = await env.DB.prepare("SELECT plex_synced_at FROM automation WHERE id = 1").first<{ plex_synced_at: number | null }>();
  if (row?.plex_synced_at && row.plex_synced_at > now - PLEX_INTERVAL) return;
  const sync = await syncPlex(env, fetcher, sender, settings.plex, now);
  await env.DB.prepare("UPDATE automation SET plex_synced_at = ?, plex_status = ?, plex_items = ? WHERE id = 1")
    .bind(now, sync.status, sync.items)
    .run();
}

/** Un passage planifié. Chaque partie est indépendante : l'échec de l'une n'arrête pas les autres. */
export async function runAutomation(env: Env, fetcher: FetchLike, sender: PushSender, now: number): Promise<void> {
  const [adminId] = await adminIds(env);
  if (!adminId) return;
  const settings = await loadSettings(env, adminId);
  if (!settings) return;
  const parts: [string, () => Promise<void>][] = [["plex", () => plexDue(env, fetcher, sender, settings, now)]];
  if (automationEnabled(settings)) {
    parts.push(["search", () => searchDue(env, fetcher, settings, now)], ["stale", () => alertStale(env, sender, now)]);
  }
  for (const [name, part] of parts) {
    try {
      await part();
    } catch (error) {
      console.error(`automation ${name} failed`, error instanceof Error ? error.name : typeof error);
    }
  }
}
