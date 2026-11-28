/**
 * Relais du catalogue TMDB : `GET /v1/tmdb/<chemin>`. Les apps n'ont pas de clé
 * TMDB ; le Worker appelle TMDB avec la sienne (secret `TMDB_API_KEY`), sur une
 * liste blanche stricte de chemins et de paramètres (jamais un relais ouvert).
 * Les réponses sont gardées dans D1, car la Cache API ne fonctionne pas sur
 * `workers.dev`. La clé n'est jamais journalisée, mise en cache ni renvoyée.
 */
import type { FetchLike } from "./apple";
import type { Env } from "./env";
import { ApiError } from "./http";
import { authenticateCatalog } from "./sessions";

export const TMDB_PREFIX = "/v1/tmdb/";

const TMDB_API = "https://api.themoviedb.org/3/";
const TIMEOUT_MS = 10_000;
/** Entrées expirées effacées à chaque écriture : D1 n'a pas de tâche de fond. */
export const PURGE_BATCH = 20;

const HOUR = 3600;
const DAY = 24 * HOUR;

/** Valeur normalisée d'un paramètre du client, ou `null` si elle est refusée. */
type Parser = (value: string) => string | null;

interface Param {
  parse: Parser;
  /** Sans ce paramètre : `invalid_request`. */
  required?: boolean;
  /** Valeur transmise quand le client n'en donne pas (une seule clé de cache par page). */
  fallback?: string;
}

/** Une route de la liste blanche, une fois le chemin du client reconnu. */
interface Route {
  /** Chemin TMDB, normalisé, sans le préfixe `/3/`. */
  path: string;
  /** Durée de vie dans le cache, en secondes ; 0 : jamais mis en cache. */
  ttl: number;
  /** Seuls paramètres relayés. Tous les autres sont ignorés, jamais transmis. */
  accepted: Record<string, Param>;
  /** Paramètres imposés par le Worker ; ils l'emportent sur ceux du client. */
  fixed: Record<string, string>;
}

function parsePage(value: string): string | null {
  if (!/^[0-9]{1,3}$/.test(value)) return null;
  const page = Number(value);
  return page >= 1 && page <= 500 ? String(page) : null;
}

/** Identifiants de genres séparés par `,` (et) ou `|` (ou), triés et sans doublon. */
function parseGenres(value: string): string | null {
  if (!/^[0-9]{1,8}(?:[,|][0-9]{1,8})*$/.test(value)) return null;
  const separator = value.includes("|") ? "|" : ",";
  if (separator === "|" && value.includes(",")) return null;
  const ids = [...new Set(value.split(separator).map(Number))].sort((a, b) => a - b);
  return ids.length <= 10 ? ids.join(separator) : null;
}

const SORTS = new Set(["popularity.desc", "vote_average.desc", "primary_release_date.desc", "first_air_date.desc"]);

function parseSort(value: string): string | null {
  return SORTS.has(value) ? value : null;
}

/** Recherche de 1 à 100 caractères, espaces de bord retirés. */
function parseQuery(value: string): string | null {
  const query = value.trim();
  const length = [...query].length;
  return length >= 1 && length <= 100 ? query : null;
}

function parseExternalSource(value: string): string | null {
  return value === "imdb_id" || value === "tvdb_id" ? value : null;
}

const PAGE: Param = { parse: parsePage, fallback: "1" };

function route(path: string, ttl: number, accepted: Record<string, Param>, fixed: Record<string, string> = {}): Route {
  return { path, ttl, accepted, fixed: { language: "fr-FR", ...fixed } };
}

const MEDIA = new Set(["movie", "tv"]);
const TRENDING_KINDS = new Set(["all", "movie", "tv"]);
const TRENDING_WINDOWS = new Set(["day", "week"]);
const LISTS = {
  movie: new Set(["popular", "now_playing", "upcoming", "top_rated"]),
  tv: new Set(["popular", "on_the_air", "airing_today", "top_rated"]),
};
const NUMBER = /^[0-9]{1,10}$/;
const SEASON_NUMBER = /^[0-9]{1,2}$/;

/** Reconnaît un chemin de la liste blanche (après `/v1/tmdb/`) ; `null` sinon. */
function resolve(path: string): Route | null {
  const parts = path.split("/");
  const [first = "", second = "", third = "", fourth = ""] = parts;

  if (parts.length === 3 && first === "trending" && TRENDING_KINDS.has(second) && TRENDING_WINDOWS.has(third)) {
    return route(path, HOUR, { page: PAGE }, { include_adult: "false" });
  }
  if (parts.length === 3 && first === "genre" && MEDIA.has(second) && third === "list") {
    return route(path, 7 * DAY, {});
  }
  if (parts.length === 2 && first === "discover" && MEDIA.has(second)) {
    const accepted = { page: PAGE, with_genres: { parse: parseGenres }, sort_by: { parse: parseSort } };
    return route(path, 6 * HOUR, accepted, { include_adult: "false", watch_region: "FR" });
  }
  if (parts.length === 2 && first === "search" && second === "multi") {
    return route(path, 0, { query: { parse: parseQuery, required: true }, page: PAGE }, { include_adult: "false" });
  }
  // Correspondance d'un identifiant IMDb ou TVDB (webhooks Plex et Tautulli).
  if (parts.length === 2 && first === "find" && /^(?:tt[0-9]{1,10}|[0-9]{1,10})$/.test(second)) {
    return route(path, 7 * DAY, { external_source: { parse: parseExternalSource, required: true } });
  }
  if (first === "movie" || first === "tv") {
    if (parts.length === 2 && LISTS[first].has(second)) {
      return route(path, 6 * HOUR, { page: PAGE }, { include_adult: "false", region: "FR" });
    }
    if (parts.length === 2 && NUMBER.test(second)) {
      return route(`${first}/${Number(second)}`, DAY, {}, {
        append_to_response: "credits,videos,watch/providers,similar",
        include_video_language: "fr,en,null",
      });
    }
    const isSeason = first === "tv" && parts.length === 4 && third === "season";
    if (isSeason && NUMBER.test(second) && SEASON_NUMBER.test(fourth)) {
      return route(`tv/${Number(second)}/season/${Number(fourth)}`, 12 * HOUR, {});
    }
  }
  return null;
}

/**
 * Paramètres transmis à TMDB : ceux du client que la route accepte (validés,
 * normalisés), puis ceux du Worker, triés par nom. Sans la clé d'API : cet
 * ensemble sert aussi de clé de cache.
 */
function upstreamParams(found: Route, query: URLSearchParams): URLSearchParams {
  const values: Record<string, string> = {};
  for (const [name, param] of Object.entries(found.accepted)) {
    const given = query.getAll(name);
    if (given.length > 1) throw new ApiError("invalid_request");
    const raw = given[0];
    if (raw === undefined) {
      if (param.required) throw new ApiError("invalid_request");
      if (param.fallback !== undefined) values[name] = param.fallback;
      continue;
    }
    const value = param.parse(raw);
    if (value === null) throw new ApiError("invalid_request");
    values[name] = value;
  }
  const merged = Object.entries({ ...values, ...found.fixed });
  return new URLSearchParams(merged.sort(([a], [b]) => (a < b ? -1 : 1)));
}

function jsonText(body: string): Response {
  return new Response(body, {
    status: 200,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

/** Seul le nom de l'erreur est journalisé, jamais l'URL ni la clé. */
function errorName(error: unknown): string {
  return error instanceof Error ? error.name : typeof error;
}

function isJsonObject(text: string): boolean {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === "object" && value !== null && !Array.isArray(value);
  } catch {
    return false;
  }
}

/**
 * Appelle TMDB et rend le corps JSON tel quel. 404 → `not_found` ; clé absente,
 * délai dépassé, réseau, 429, 5xx, autre refus ou JSON illisible → `provider_unavailable`.
 */
async function fetchTmdb(env: Env, fetcher: FetchLike, path: string, params: URLSearchParams): Promise<string> {
  const key = env.TMDB_API_KEY?.trim();
  if (!key) throw new ApiError("provider_unavailable");

  // Jeton v4 (JWT) dans l'en-tête, clé v3 dans l'URL.
  const headers: Record<string, string> = { accept: "application/json" };
  const url = new URL(`${TMDB_API}${path}`);
  url.search = params.toString();
  if (key.startsWith("eyJ")) headers.authorization = `Bearer ${key}`;
  else url.searchParams.set("api_key", key);

  try {
    const response = await fetcher(url.toString(), { method: "GET", headers, signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (response.status === 404) throw new ApiError("not_found");
    if (!response.ok) {
      console.warn("tmdb upstream status", response.status);
      throw new ApiError("provider_unavailable");
    }
    const text = await response.text();
    if (!isJsonObject(text)) {
      console.warn("tmdb upstream body unreadable");
      throw new ApiError("provider_unavailable");
    }
    return text;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    console.warn("tmdb upstream failure", errorName(error));
    throw new ApiError("provider_unavailable");
  }
}

/** Une panne de D1 n'est jamais fatale : on traite l'entrée comme absente. */
async function readCache(env: Env, key: string, now: number): Promise<string | null> {
  try {
    const row = await env.DB.prepare("SELECT body FROM tmdb_cache WHERE cache_key = ? AND expires_at > ?")
      .bind(key, now)
      .first<{ body: string }>();
    return row?.body ?? null;
  } catch (error) {
    console.warn("tmdb cache read failed", errorName(error));
    return null;
  }
}

/** Écrit l'entrée et purge quelques entrées expirées ; n'échoue jamais. */
async function writeCache(env: Env, key: string, body: string, ttl: number, now: number): Promise<void> {
  try {
    await env.DB.batch([
      env.DB.prepare("INSERT OR REPLACE INTO tmdb_cache (cache_key, body, expires_at) VALUES (?, ?, ?)").bind(
        key,
        body,
        now + ttl,
      ),
      env.DB.prepare(
        "DELETE FROM tmdb_cache WHERE cache_key IN (SELECT cache_key FROM tmdb_cache WHERE expires_at <= ? LIMIT ?)",
      ).bind(now, PURGE_BATCH),
    ]);
  } catch (error) {
    console.warn("tmdb cache write failed", errorName(error));
  }
}

/**
 * `GET /v1/tmdb/<chemin>`. Le jeton d'accès est vérifié avant tout accès au
 * cache ou au réseau ; un chemin hors liste donne `not_found`, un paramètre
 * accepté mais hors bornes `invalid_request`.
 */
export async function relayTmdb(request: Request, env: Env, fetcher: FetchLike, now: number): Promise<Response> {
  await authenticateCatalog(request, env, now);
  const url = new URL(request.url);
  return jsonText(await cachedTmdb(env, fetcher, url.pathname.slice(TMDB_PREFIX.length), url.searchParams, now));
}

/**
 * Corps TMDB d'un chemin de la liste blanche (après `/v1/tmdb/`), lu dans le
 * cache ou chez TMDB. Sert au relais et au Worker lui-même (fiche d'un titre
 * demandé), avec les mêmes clés de cache.
 */
export async function cachedTmdb(
  env: Env,
  fetcher: FetchLike,
  path: string,
  query: URLSearchParams,
  now: number,
): Promise<string> {
  const found = resolve(path);
  if (!found) throw new ApiError("not_found");

  const params = upstreamParams(found, query);
  const cacheKey = `${found.path}?${params.toString()}`;
  if (found.ttl > 0) {
    const cached = await readCache(env, cacheKey, now);
    if (cached !== null) return cached;
  }
  const body = await fetchTmdb(env, fetcher, found.path, params);
  if (found.ttl > 0) await writeCache(env, cacheKey, body, found.ttl, now);
  return body;
}

export const POSTERS_PATH = "/v1/posters";
/** Affiches au plus dans la réponse publique. */
export const MAX_POSTERS = 24;
const POSTER_PATH = /^\/[A-Za-z0-9_-]+\.(?:jpg|png)$/;

/**
 * `GET /v1/posters`, sans compte : les chemins des affiches en tendance de la
 * semaine (`/abc.jpg`), pour le décor de l'écran de connexion. Rien d'autre
 * que ces chemins ne sort : ni titre, ni identifiant. La réponse vient du même
 * cache que le relais (une heure) : TMDB est appelé au plus une fois par heure,
 * quel que soit le nombre d'appels. Une entrée illisible est ignorée.
 */
export async function welcomePosters(env: Env, fetcher: FetchLike, now: number): Promise<Response> {
  const body = await cachedTmdb(env, fetcher, "trending/all/week", new URLSearchParams(), now);
  const results: unknown = (JSON.parse(body) as { results?: unknown }).results;
  const posters: string[] = [];
  if (Array.isArray(results)) {
    for (const entry of results) {
      if (typeof entry !== "object" || entry === null) continue;
      const { poster_path: path, media_type: kind } = entry as { poster_path?: unknown; media_type?: unknown };
      if ((kind === "movie" || kind === "tv") && typeof path === "string" && POSTER_PATH.test(path)) {
        posters.push(path);
      }
      if (posters.length >= MAX_POSTERS) break;
    }
  }
  return jsonText(JSON.stringify({ posters }));
}
