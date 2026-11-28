/**
 * Watchlist et demandes. Ajouter un titre à sa watchlist crée sa demande, ou
 * rattache la personne à la demande existante (une seule par titre). Le titre,
 * l'affiche et l'année viennent de TMDB par le Worker, jamais de l'app.
 *
 * Synchronisation : D1 est la source de vérité ; chaque écriture est
 * idempotente (l'app rejoue sa file d'attente hors ligne sans risque) et la
 * dernière reçue par le Worker l'emporte.
 */
import type { FetchLike } from "./apple";
import type { Env } from "./env";
import { ApiError, json, noContent, readJson } from "./http";
import { authenticate } from "./sessions";
import { airedEpisodes, missingSeasons, type AiredEpisodes } from "./seasons";
import { cachedTmdb } from "./tmdb";

export const WATCHLIST_PATH = "/v1/watchlist";

/** Entrées renvoyées au plus par `GET /v1/watchlist`. */
const MAX_ITEMS = 1000;
const MAX_SEASON = 99;

export type MediaType = "movie" | "tv";
export type RequestStatus = "requested" | "in_progress" | "available" | "refused";

/** Une entrée de la watchlist, telle que l'API la renvoie. */
export interface WatchlistItem {
  media_type: MediaType;
  tmdb_id: number;
  title: string;
  poster_path: string | null;
  year: number | null;
  /** Saisons voulues d'une série ; `null` : film ou série entière. */
  seasons: number[] | null;
  added_at: number;
  status: RequestStatus;
  refusal_reason: string | null;
}

/** `/v1/watchlist/{movie|tv}/{id}` → type et identifiant ; `null` si le chemin n'est pas de cette forme. */
export function parseItemPath(pathname: string): { mediaType: MediaType; tmdbId: number } | null {
  const match = /^\/v1\/watchlist\/(movie|tv)\/([0-9]{1,10})$/.exec(pathname);
  if (!match?.[1] || !match[2]) return null;
  const tmdbId = Number(match[2]);
  if (tmdbId < 1) return null;
  return { mediaType: match[1] as MediaType, tmdbId };
}

/** Saisons du corps : absent ou `null` → série entière ; sinon liste non vide d'entiers 0 à 99, triée, sans doublon. */
function parseSeasons(body: Record<string, unknown>, mediaType: MediaType): number[] | null {
  const value = body.seasons;
  if (value === undefined || value === null) return null;
  if (mediaType === "movie" || !Array.isArray(value) || value.length === 0 || value.length > MAX_SEASON + 1) {
    throw new ApiError("invalid_request");
  }
  for (const season of value) {
    if (typeof season !== "number" || !Number.isInteger(season) || season < 0 || season > MAX_SEASON) {
      throw new ApiError("invalid_request");
    }
  }
  return [...new Set(value as number[])].sort((a, b) => a - b);
}

/** Ce que le Worker garde d'une fiche TMDB. */
interface TmdbTitle {
  title: string;
  posterPath: string | null;
  year: number | null;
  /** Numéros des saisons connues de TMDB (séries). */
  seasons: Set<number>;
  /** Épisodes déjà diffusés par saison (séries). */
  aired: AiredEpisodes;
}

function text(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed.slice(0, 300) : null;
}

/** Fiche TMDB relue par le Worker ; `not_found` si elle est illisible. */
function readTmdbTitle(body: string, mediaType: MediaType): TmdbTitle {
  let root: Record<string, unknown>;
  try {
    root = JSON.parse(body) as Record<string, unknown>;
  } catch {
    throw new ApiError("provider_unavailable");
  }
  const title = mediaType === "movie" ? (text(root.title) ?? text(root.name)) : (text(root.name) ?? text(root.title));
  if (!title) throw new ApiError("not_found");
  const poster = text(root.poster_path);
  const date = text(mediaType === "movie" ? root.release_date : root.first_air_date);
  const yearMatch = date ? /^([0-9]{4})-[0-9]{2}-[0-9]{2}$/.exec(date) : null;
  const seasons = new Set<number>();
  if (Array.isArray(root.seasons)) {
    for (const season of root.seasons as unknown[]) {
      const number = (season as { season_number?: unknown } | null)?.season_number;
      if (typeof number === "number" && Number.isInteger(number)) seasons.add(number);
    }
  }
  return {
    title,
    posterPath: poster && /^\/[A-Za-z0-9._-]+$/.test(poster) && !poster.includes("..") ? poster : null,
    year: yearMatch?.[1] ? Number(yearMatch[1]) : null,
    seasons,
    aired: airedEpisodes(root),
  };
}

interface ItemRow {
  media_type: MediaType;
  tmdb_id: number;
  title: string;
  poster_path: string | null;
  year: number | null;
  seasons: string | null;
  added_at: number;
  status: RequestStatus;
  refusal_reason: string | null;
}

const ITEM_SELECT = `SELECT w.media_type, w.tmdb_id, r.title, r.poster_path, r.year, w.seasons, w.added_at, r.status, r.refusal_reason
  FROM watchlist_items w JOIN requests r ON r.id = w.request_id
  WHERE w.user_id = ? AND w.deleted_at IS NULL`;

/** Une valeur illisible de `seasons` (jamais écrite par ce Worker) vaut « série entière ». */
function seasonsOf(raw: string | null): number[] | null {
  if (raw === null) return null;
  try {
    const value: unknown = JSON.parse(raw);
    return Array.isArray(value) && value.every((n) => typeof n === "number") ? (value as number[]) : null;
  } catch {
    return null;
  }
}

function item(row: ItemRow): WatchlistItem {
  return { ...row, seasons: seasonsOf(row.seasons) };
}

/** `GET /v1/watchlist` : toute la watchlist du compte, la plus récente d'abord. */
async function list(request: Request, env: Env, now: number): Promise<Response> {
  const claims = await authenticate(request, env, now);
  const rows = await env.DB.prepare(`${ITEM_SELECT} ORDER BY w.added_at DESC, w.tmdb_id LIMIT ${MAX_ITEMS}`)
    .bind(claims.userId)
    .all<ItemRow>();
  return json({ items: rows.results.map(item) });
}

/**
 * `PUT /v1/watchlist/{type}/{id}` `{seasons?}` : ajoute le titre (ou change ses
 * saisons) et le rattache à sa demande, créée s'il n'y en a pas.
 */
async function put(
  request: Request,
  env: Env,
  fetcher: FetchLike,
  now: number,
  mediaType: MediaType,
  tmdbId: number,
): Promise<Response> {
  const claims = await authenticate(request, env, now);
  const seasons = parseSeasons(await readJson(request), mediaType);
  const tmdb = readTmdbTitle(await cachedTmdb(env, fetcher, `${mediaType}/${tmdbId}`, new URLSearchParams(), now), mediaType);
  if (seasons && seasons.some((season) => !tmdb.seasons.has(season))) throw new ApiError("invalid_request");

  // Un seul lot, donc une transaction : la demande existe forcément quand
  // l'entrée la désigne, même si quelqu'un retire le titre au même moment.
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO requests (id, media_type, tmdb_id, title, poster_path, year, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, 'requested', ?, ?)
       ON CONFLICT (media_type, tmdb_id) DO UPDATE SET
         title = excluded.title, poster_path = excluded.poster_path, year = excluded.year`,
    ).bind(crypto.randomUUID(), mediaType, tmdbId, tmdb.title, tmdb.posterPath, tmdb.year, now, now),
    env.DB.prepare(
      `INSERT INTO watchlist_items (user_id, media_type, tmdb_id, request_id, seasons, added_at, updated_at, deleted_at)
       VALUES (?1, ?2, ?3, (SELECT id FROM requests WHERE media_type = ?2 AND tmdb_id = ?3), ?4, ?5, ?5, NULL)
       ON CONFLICT (user_id, media_type, tmdb_id) DO UPDATE SET
         request_id = excluded.request_id,
         seasons = excluded.seasons,
         added_at = CASE WHEN watchlist_items.deleted_at IS NULL THEN watchlist_items.added_at ELSE excluded.added_at END,
         updated_at = excluded.updated_at,
         deleted_at = NULL`,
    ).bind(claims.userId, mediaType, tmdbId, seasons ? JSON.stringify(seasons) : null, now),
  ]);

  if (mediaType === "tv") await reopenIfMissing(env, tmdbId, seasons, tmdb.aired, now);

  const row = await env.DB.prepare(`${ITEM_SELECT} AND w.media_type = ? AND w.tmdb_id = ?`)
    .bind(claims.userId, mediaType, tmdbId)
    .first<ItemRow>();
  if (!row) throw new ApiError("internal");
  return json({ item: item(row) });
}

/**
 * Série déjà « disponible » dont la personne veut une saison pas encore sur
 * Plex (nouvelle saison, saison oubliée) : la demande revient dans la file de
 * l'administrateur, comme après une réouverture.
 */
async function reopenIfMissing(
  env: Env,
  tmdbId: number,
  seasons: number[] | null,
  aired: AiredEpisodes,
  now: number,
): Promise<void> {
  const request = await env.DB.prepare("SELECT status FROM requests WHERE media_type = 'tv' AND tmdb_id = ?")
    .bind(tmdbId)
    .first<{ status: RequestStatus }>();
  if (request?.status !== "available") return;
  if ((await missingSeasons(env, tmdbId, seasons, aired)).length === 0) return;
  await env.DB.prepare(
    `UPDATE requests SET status = 'requested', release_title = NULL, torrent_hash = NULL, updated_at = ?
     WHERE media_type = 'tv' AND tmdb_id = ? AND status = 'available'`,
  )
    .bind(now, tmdbId)
    .run();
}

/**
 * Demande que personne n'attend plus et que l'administrateur n'a pas encore
 * lancée : elle disparaît. Une demande refusée part aussi, pour qu'un nouvel
 * ajout la reformule.
 */
function orphanRequestsDelete(env: Env, mediaType?: MediaType, tmdbId?: number): D1PreparedStatement {
  const scope = mediaType === undefined ? "" : "AND media_type = ? AND tmdb_id = ?";
  const statement = env.DB.prepare(
    `DELETE FROM requests WHERE status IN ('requested', 'refused') ${scope}
     AND NOT EXISTS (SELECT 1 FROM watchlist_items w WHERE w.request_id = requests.id AND w.deleted_at IS NULL)`,
  );
  return mediaType === undefined ? statement : statement.bind(mediaType, tmdbId);
}

/** `DELETE /v1/watchlist/{type}/{id}` : retire le titre ; sans effet s'il n'y était pas. */
async function remove(request: Request, env: Env, now: number, mediaType: MediaType, tmdbId: number): Promise<Response> {
  const claims = await authenticate(request, env, now);
  await env.DB.batch([
    env.DB.prepare(
      `UPDATE watchlist_items SET deleted_at = ?, updated_at = ?
       WHERE user_id = ? AND media_type = ? AND tmdb_id = ? AND deleted_at IS NULL`,
    ).bind(now, now, claims.userId, mediaType, tmdbId),
    orphanRequestsDelete(env, mediaType, tmdbId),
  ]);
  return noContent();
}

/** Après la suppression d'un compte : ses demandes que plus personne n'attend. */
export async function deleteOrphanRequests(env: Env): Promise<void> {
  await orphanRequestsDelete(env).run();
}

/** Routes `/v1/watchlist…` ; `undefined` si la méthode ou le chemin n'existe pas. */
export function watchlistHandler(
  method: string,
  pathname: string,
): ((request: Request, env: Env, fetcher: FetchLike, now: number) => Promise<Response>) | undefined {
  if (pathname === WATCHLIST_PATH) {
    return method === "GET" ? (request, env, _fetcher, now) => list(request, env, now) : undefined;
  }
  const target = parseItemPath(pathname);
  if (!target) return undefined;
  if (method === "PUT") {
    return (request, env, fetcher, now) => put(request, env, fetcher, now, target.mediaType, target.tmdbId);
  }
  if (method === "DELETE") {
    return (request, env, _fetcher, now) => remove(request, env, now, target.mediaType, target.tmdbId);
  }
  return undefined;
}
