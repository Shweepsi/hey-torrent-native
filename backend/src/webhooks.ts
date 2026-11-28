/**
 * Webhooks des nouveaux ajouts sur Plex : `POST /v1/hooks/plex/<jeton>` (webhook
 * Plex Pass, événement `library.new`, source principale) et
 * `POST /v1/hooks/tautulli/<jeton>` (« Recently added » de Tautulli, secours).
 * Les deux mènent au même traitement (`availability.ts`), dédoublonné.
 *
 * Le jeton secret de l'URL est créé par l'administrateur
 * (`POST /v1/notifications/webhook`) ; seul son condensé est gardé. Une URL
 * inconnue ou révoquée reçoit `not_found`, sans détail. Une charge illisible
 * ou un média non reconnu est ignoré (réponse 200 : Plex n'a rien à corriger).
 */
import type { FetchLike } from "./apple";
import { processArrival, type Arrival } from "./availability";
import { randomToken, sha256Hex } from "./crypto";
import type { Env } from "./env";
import { ApiError, json } from "./http";
import type { PushSender } from "./push";
import { authenticate } from "./sessions";
import { cachedTmdb } from "./tmdb";

export const HOOKS_PREFIX = "/v1/hooks/";
export const WEBHOOK_SETTINGS_PATH = "/v1/notifications/webhook";

/** Le webhook Plex joint parfois une vignette : on refuse au-delà. */
const MAX_BODY_BYTES = 2 * 1024 * 1024;

type Source = "plex" | "tautulli";

/**
 * Média tel que la source le décrit. `episodeIds` : les identifiants sont ceux
 * de l'épisode (Plex) et non de la série (Tautulli).
 */
export interface Sighting {
  kind: "movie" | "show" | "season" | "episode";
  title: string;
  tmdb?: number | undefined;
  tvdb?: number | undefined;
  imdb?: string | undefined;
  season?: number | undefined;
  episode?: number | undefined;
  episodeIds: boolean;
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function text(value: unknown): string {
  return typeof value === "string" ? value.trim().slice(0, 200) : "";
}

/** Entier positif (ou nul pour une saison 0) d'un nombre ou d'un texte. */
function integer(value: unknown, min = 1): number | undefined {
  const number = typeof value === "number" ? value : typeof value === "string" && /^[0-9]{1,10}$/.test(value.trim()) ? Number(value) : NaN;
  return Number.isInteger(number) && number >= min && number <= 9_999_999_999 ? number : undefined;
}

function imdbId(value: unknown): string | undefined {
  const raw = text(value);
  return /^tt[0-9]{1,10}$/.test(raw) ? raw : undefined;
}

const KINDS = new Set(["movie", "show", "season", "episode"]);

/** Identifiants `tmdb://`, `tvdb://`, `imdb://` (agents Plex récents). */
function readGuids(guids: unknown, into: Sighting): void {
  if (!Array.isArray(guids)) return;
  for (const entry of guids) {
    const id = text(record(entry)?.id);
    const match = /^(tmdb|tvdb|imdb):\/\/(.+)$/.exec(id);
    if (!match?.[1] || !match[2]) continue;
    if (match[1] === "tmdb") into.tmdb ??= integer(match[2]);
    else if (match[1] === "tvdb") into.tvdb ??= integer(match[2]);
    else into.imdb ??= imdbId(match[2]);
  }
}

/**
 * Ancien format `com.plexapp.agents.<agent>://<id>[/<saison>[/<épisode>]]?…` :
 * l'identifiant est alors celui du film ou de la série.
 */
function readLegacyGuid(guid: unknown, into: Sighting): void {
  const match = /^com\.plexapp\.agents\.(themoviedb|thetvdb|imdb):\/\/([^/?]+)/.exec(text(guid));
  if (!match?.[1] || !match[2]) return;
  into.episodeIds = false;
  if (match[1] === "themoviedb") into.tmdb ??= integer(match[2]);
  else if (match[1] === "thetvdb") into.tvdb ??= integer(match[2]);
  else into.imdb ??= imdbId(match[2]);
}

/**
 * Identifiant TMDB d'un élément de bibliothèque Plex (film ou série), lu dans
 * ses `Guid` ou son ancien `guid` ; `undefined` sans identifiant TMDB.
 */
export function plexTmdbId(metadata: unknown): number | undefined {
  const item = record(metadata);
  if (!item) return undefined;
  const sighting: Sighting = { kind: "movie", title: "", episodeIds: false };
  readGuids(item.Guid, sighting);
  readLegacyGuid(item.guid, sighting);
  return sighting.tmdb;
}

/** Charge `payload` d'un webhook Plex ; `null` si ce n'est pas un nouvel ajout. */
export function readPlex(payload: unknown): Sighting | null {
  const body = record(payload);
  if (!body || body.event !== "library.new") return null;
  const metadata = record(body.Metadata);
  const kind = text(metadata?.type);
  if (!metadata || !KINDS.has(kind)) return null;
  const sighting: Sighting = {
    kind: kind as Sighting["kind"],
    title: text(kind === "episode" ? metadata.grandparentTitle : kind === "season" ? metadata.parentTitle : metadata.title),
    episodeIds: kind === "episode" || kind === "season",
  };
  if (kind === "episode") {
    sighting.season = integer(metadata.parentIndex, 0);
    sighting.episode = integer(metadata.index, 0);
  } else if (kind === "season") {
    sighting.season = integer(metadata.index, 0);
  }
  readGuids(metadata.Guid, sighting);
  readLegacyGuid(metadata.guid, sighting);
  return sighting;
}

/** Corps JSON du modèle Tautulli (voir `tautulliTemplate`). */
export function readTautulli(payload: unknown): Sighting | null {
  const body = record(payload);
  if (!body) return null;
  if (body.event !== undefined && body.event !== "recently_added") return null;
  const kind = text(body.media_type);
  if (!KINDS.has(kind)) return null;
  const sighting: Sighting = {
    kind: kind as Sighting["kind"],
    title: text(kind === "movie" || kind === "show" ? body.title : body.show_name) || text(body.title),
    // Tautulli donne les identifiants du film ou de la série.
    episodeIds: false,
  };
  const tmdb = integer(body.tmdb_id);
  const tvdb = integer(body.tvdb_id);
  const imdb = imdbId(body.imdb_id);
  if (tmdb !== undefined) sighting.tmdb = tmdb;
  if (tvdb !== undefined) sighting.tvdb = tvdb;
  if (imdb !== undefined) sighting.imdb = imdb;
  if (kind === "season" || kind === "episode") {
    const season = integer(body.season, 0);
    if (season !== undefined) sighting.season = season;
  }
  if (kind === "episode") {
    const episode = integer(body.episode, 0);
    if (episode !== undefined) sighting.episode = episode;
  }
  return sighting;
}

/** Modèle de corps JSON à coller dans Tautulli (« Recently Added »). */
export const TAUTULLI_TEMPLATE = JSON.stringify(
  {
    event: "recently_added",
    media_type: "{media_type}",
    title: "{title}",
    show_name: "{show_name}",
    season: "{season_num}",
    episode: "{episode_num}",
    tmdb_id: "{themoviedb_id}",
    tvdb_id: "{thetvdb_id}",
    imdb_id: "{imdb_id}",
  },
  null,
  2,
);

interface FindResult {
  movie_results?: { id?: unknown }[];
  tv_results?: { id?: unknown }[];
  tv_episode_results?: { show_id?: unknown; season_number?: unknown; episode_number?: unknown }[];
}

/** Recherche TMDB par identifiant externe ; `null` si TMDB ne répond pas. */
async function find(env: Env, fetcher: FetchLike, id: string, source: "imdb_id" | "tvdb_id", now: number): Promise<FindResult | null> {
  try {
    const body = await cachedTmdb(env, fetcher, `find/${id}`, new URLSearchParams({ external_source: source }), now);
    return record(JSON.parse(body)) as FindResult;
  } catch {
    return null;
  }
}

/** Traduit ce que dit la source en identifiants TMDB ; `null` si le média reste inconnu. */
export async function resolveSighting(env: Env, fetcher: FetchLike, sighting: Sighting, now: number): Promise<Arrival | null> {
  const lookups: [string, "imdb_id" | "tvdb_id"][] = [];
  if (sighting.tvdb !== undefined) lookups.push([String(sighting.tvdb), "tvdb_id"]);
  if (sighting.imdb !== undefined) lookups.push([sighting.imdb, "imdb_id"]);

  if (sighting.kind === "movie") {
    let tmdbId = sighting.tmdb;
    for (const [id, source] of lookups.filter(([, s]) => s === "imdb_id")) {
      if (tmdbId !== undefined) break;
      tmdbId = integer((await find(env, fetcher, id, source, now))?.movie_results?.[0]?.id);
    }
    return tmdbId === undefined ? null : { mediaType: "movie", tmdbId, title: sighting.title };
  }

  // Série : le TMDB donné est celui de la série, sauf s'il désigne l'épisode.
  let showId = sighting.episodeIds ? undefined : sighting.tmdb;
  let season = sighting.season;
  let episode = sighting.episode;
  for (const [id, source] of lookups) {
    if (showId !== undefined) break;
    const found = await find(env, fetcher, id, source, now);
    const asEpisode = found?.tv_episode_results?.[0];
    showId = integer(asEpisode?.show_id) ?? integer(found?.tv_results?.[0]?.id);
    if (asEpisode && showId !== undefined) {
      season ??= integer(asEpisode.season_number, 0);
      episode ??= integer(asEpisode.episode_number, 0);
    }
  }
  if (showId === undefined) return null;
  const arrival: Arrival = { mediaType: "tv", tmdbId: showId, title: sighting.title };
  if (sighting.kind !== "show" && season !== undefined) {
    arrival.season = season;
    if (sighting.kind === "episode" && episode !== undefined) arrival.episode = episode;
  }
  return arrival;
}

/** Jeton actif correspondant, marqué utilisé ; sinon `not_found`. */
async function checkToken(env: Env, token: string, now: number): Promise<void> {
  if (!/^[A-Za-z0-9_-]{20,100}$/.test(token)) throw new ApiError("not_found");
  const result = await env.DB.prepare(
    "UPDATE webhook_tokens SET last_used_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
  )
    .bind(now, await sha256Hex(token))
    .run();
  if (result.meta.changes !== 1) throw new ApiError("not_found");
}

/** Charge utile : champ `payload` du formulaire de Plex, corps JSON de Tautulli. */
async function readPayload(request: Request, source: Source): Promise<unknown> {
  const length = Number(request.headers.get("content-length") ?? "0");
  if (length > MAX_BODY_BYTES) return null;
  try {
    if (source === "plex") {
      const type = request.headers.get("content-type") ?? "";
      if (type.includes("application/json")) return await request.json();
      const form = await request.formData();
      const payload = form.get("payload");
      return typeof payload === "string" ? JSON.parse(payload) : null;
    }
    return await request.json();
  } catch {
    return null;
  }
}

export async function receiveHook(
  request: Request,
  env: Env,
  fetcher: FetchLike,
  sender: PushSender,
  now: number,
): Promise<Response> {
  const match = /^\/v1\/hooks\/(plex|tautulli)\/([^/]+)$/.exec(new URL(request.url).pathname);
  if (!match?.[1] || !match[2]) throw new ApiError("not_found");
  const source = match[1] as Source;
  await checkToken(env, match[2], now);

  const payload = await readPayload(request, source);
  const sighting = source === "plex" ? readPlex(payload) : readTautulli(payload);
  if (!sighting) return json({ status: "ignored" });
  const arrival = await resolveSighting(env, fetcher, sighting, now);
  if (!arrival) {
    console.warn("hook: media not recognised", source, sighting.kind);
    return json({ status: "unknown_media" });
  }
  return json({ status: await processArrival(env, fetcher, sender, arrival, now) });
}

async function requireAdmin(request: Request, env: Env, now: number): Promise<string> {
  const claims = await authenticate(request, env, now);
  const row = await env.DB.prepare("SELECT role FROM users WHERE id = ?").bind(claims.userId).first<{ role: string }>();
  if (!row) throw new ApiError("unauthorized");
  if (row.role !== "admin") throw new ApiError("forbidden");
  return claims.userId;
}

/** État du webhook (administrateur) : jamais l'URL, qui n'est montrée qu'à sa création. */
export async function webhookStatus(request: Request, env: Env, now: number): Promise<Response> {
  await requireAdmin(request, env, now);
  const row = await env.DB.prepare(
    "SELECT created_at, last_used_at FROM webhook_tokens WHERE revoked_at IS NULL ORDER BY created_at DESC LIMIT 1",
  ).first<{ created_at: number; last_used_at: number | null }>();
  return json({ configured: row !== null, created_at: row?.created_at ?? null, last_used_at: row?.last_used_at ?? null });
}

/** Crée un nouveau jeton (l'ancien est révoqué) et rend les URL à coller dans Plex et Tautulli. */
export async function regenerateWebhook(request: Request, env: Env, now: number): Promise<Response> {
  const userId = await requireAdmin(request, env, now);
  const token = randomToken();
  await env.DB.batch([
    env.DB.prepare("UPDATE webhook_tokens SET revoked_at = ? WHERE revoked_at IS NULL").bind(now),
    env.DB.prepare("INSERT INTO webhook_tokens (token_hash, created_by, created_at) VALUES (?, ?, ?)").bind(
      await sha256Hex(token),
      userId,
      now,
    ),
  ]);
  const origin = new URL(request.url).origin;
  return json(
    {
      plex_url: `${origin}${HOOKS_PREFIX}plex/${token}`,
      tautulli_url: `${origin}${HOOKS_PREFIX}tautulli/${token}`,
      tautulli_body: TAUTULLI_TEMPLATE,
    },
    201,
  );
}
