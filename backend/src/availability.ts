/**
 * Arrivée d'un média sur Plex : mise à jour de `library`, dédoublonnage,
 * passage de la demande en « disponible » et push vers chaque personne qui
 * l'attend (schéma des demandes : migration 0003).
 */
import type { FetchLike } from "./apple";
import type { Env } from "./env";
import type { PushMessage, PushSender, Platform } from "./push";
import { airedEpisodesOf, missingSeasons, wantedSeasons } from "./seasons";
import type { MediaType, RequestStatus } from "./watchlist";

/** Fenêtre pendant laquelle les arrivées d'une même saison ne font qu'une push. */
export const GROUPING_WINDOW = 6 * 3600;

/** Un média reconnu, en identifiants TMDB. */
export interface Arrival {
  mediaType: MediaType;
  tmdbId: number;
  /** Série : saison arrivée (absente : la série entière). */
  season?: number;
  /** Série : épisode arrivé dans `season`. */
  episode?: number;
  /** Titre du film ou de la série donné par Plex ; vide s'il manque. */
  title: string;
}

export type ArrivalOutcome = "duplicate" | "grouped" | "no_request" | "notified";

export function eventKey(arrival: Arrival): string {
  if (arrival.mediaType === "movie") return `movie:${arrival.tmdbId}`;
  if (arrival.season === undefined) return `tv:${arrival.tmdbId}`;
  if (arrival.episode === undefined) return `tv:${arrival.tmdbId}:s${arrival.season}`;
  return `tv:${arrival.tmdbId}:s${arrival.season}e${arrival.episode}`;
}

function message(arrival: Arrival, title: string): PushMessage {
  const base = { title, collapseKey: `${arrival.mediaType}-${arrival.tmdbId}`, mediaType: arrival.mediaType, tmdbId: arrival.tmdbId };
  if (arrival.mediaType === "movie") return { ...base, bodyKey: "push_available_movie", bodyArgs: [] };
  if (arrival.season === undefined) return { ...base, bodyKey: "push_available_show", bodyArgs: [] };
  if (arrival.episode === undefined) {
    return { ...base, bodyKey: "push_available_season", bodyArgs: [String(arrival.season)] };
  }
  return { ...base, bodyKey: "push_available_episode", bodyArgs: [String(arrival.season), String(arrival.episode)] };
}

/** Personnes qui attendent ce média (et, pour une série, cette saison). */
async function waitingUsers(env: Env, arrival: Arrival): Promise<string[]> {
  const season = arrival.mediaType === "tv" ? (arrival.season ?? null) : null;
  const { results } = await env.DB.prepare(
    `SELECT DISTINCT w.user_id AS user_id
     FROM watchlist_items w JOIN requests r ON r.id = w.request_id
     WHERE r.media_type = ?1 AND r.tmdb_id = ?2 AND w.deleted_at IS NULL
       AND (?3 IS NULL OR w.seasons IS NULL OR EXISTS (SELECT 1 FROM json_each(w.seasons) WHERE value = ?3))`,
  )
    .bind(arrival.mediaType, arrival.tmdbId, season)
    .all<{ user_id: string }>();
  return results.map((row) => row.user_id);
}

/** Une push est déjà partie pour cette série (cette saison) il y a peu. */
async function recentlyPushed(env: Env, arrival: Arrival, key: string, now: number): Promise<boolean> {
  if (arrival.mediaType !== "tv") return false;
  const row = await env.DB.prepare(
    `SELECT 1 AS hit FROM notified
     WHERE media_type = 'tv' AND tmdb_id = ?1 AND pushed = 1 AND created_at > ?2 AND event_key <> ?3
       AND (?4 IS NULL OR season IS NULL OR season = ?4)
     LIMIT 1`,
  )
    .bind(arrival.tmdbId, now - GROUPING_WINDOW, key, arrival.season ?? null)
    .first<{ hit: number }>();
  return row !== null;
}

interface DeviceRow {
  token: string;
  platform: Platform;
}

/** Appareils des personnes `users` dont la session est encore valable. */
async function devicesOf(env: Env, users: string[], now: number): Promise<DeviceRow[]> {
  if (users.length === 0) return [];
  const { results } = await env.DB.prepare(
    `SELECT d.token AS token, d.platform AS platform
     FROM devices d JOIN sessions s ON s.id = d.session_id
     WHERE d.user_id IN (SELECT value FROM json_each(?1)) AND s.revoked_at IS NULL AND s.expires_at > ?2`,
  )
    .bind(JSON.stringify(users), now)
    .all<DeviceRow>();
  return results;
}

/** Envoie `message` à ces appareils ; oublie ceux dont le jeton est périmé. */
export async function pushTo(env: Env, sender: PushSender, devices: DeviceRow[], push: PushMessage): Promise<number> {
  const outcomes = await Promise.all(devices.map((d) => sender.send(d.platform, d.token, push)));
  const gone = devices.filter((_, index) => outcomes[index] === "gone").map((d) => d.token);
  if (gone.length > 0) {
    await env.DB.prepare("DELETE FROM devices WHERE token IN (SELECT value FROM json_each(?))")
      .bind(JSON.stringify(gone))
      .run();
  }
  return outcomes.filter((outcome) => outcome === "sent").length;
}

/** Appareils encore valables de ces personnes (accès, demandes d'accès). */
export function devicesOfUsers(env: Env, userIds: string[], now: number): Promise<DeviceRow[]> {
  return devicesOf(env, userIds, now);
}

/** Appareils encore valables d'une seule personne (push d'essai). */
export function devicesOfUser(env: Env, userId: string, now: number): Promise<DeviceRow[]> {
  return devicesOf(env, [userId], now);
}

/**
 * Passe la demande en « disponible » : un film dès son arrivée, une série
 * quand chaque saison voulue est sur Plex en entier. Si TMDB ne répond pas,
 * l'état ne change pas : la prochaine arrivée réessaiera.
 */
async function markAvailable(
  env: Env,
  fetcher: FetchLike,
  arrival: Arrival,
  request: { id: string; status: RequestStatus },
  now: number,
): Promise<void> {
  if (request.status === "available") return;
  if (arrival.mediaType === "tv") {
    const aired = await airedEpisodesOf(env, fetcher, arrival.tmdbId, now);
    if (!aired) return;
    const missing = await missingSeasons(env, arrival.tmdbId, await wantedSeasons(env, request.id), aired);
    if (missing.length > 0) return;
  }
  await env.DB.prepare("UPDATE requests SET status = 'available', updated_at = ? WHERE id = ? AND status <> 'available'")
    .bind(now, request.id)
    .run();
}

export async function processArrival(
  env: Env,
  fetcher: FetchLike,
  sender: PushSender,
  arrival: Arrival,
  now: number,
): Promise<ArrivalOutcome> {
  const key = eventKey(arrival);
  const [, inserted] = await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO library (media_type, tmdb_id, first_seen_at, last_seen_at) VALUES (?1, ?2, ?3, ?3)
       ON CONFLICT (media_type, tmdb_id) DO UPDATE SET last_seen_at = ?3`,
    ).bind(arrival.mediaType, arrival.tmdbId, now),
    env.DB.prepare(
      "INSERT OR IGNORE INTO notified (event_key, media_type, tmdb_id, season, created_at) VALUES (?, ?, ?, ?, ?)",
    ).bind(key, arrival.mediaType, arrival.tmdbId, arrival.season ?? null, now),
  ]);
  if (!inserted || inserted.meta.changes !== 1) return "duplicate";

  const request = await env.DB.prepare("SELECT id, title, status FROM requests WHERE media_type = ? AND tmdb_id = ?")
    .bind(arrival.mediaType, arrival.tmdbId)
    .first<{ id: string; title: string; status: RequestStatus }>();
  if (!request) return "no_request";
  await markAvailable(env, fetcher, arrival, request, now);

  if (await recentlyPushed(env, arrival, key, now)) return "grouped";
  const devices = await devicesOf(env, await waitingUsers(env, arrival), now);
  if (devices.length === 0) return "notified";
  await env.DB.prepare("UPDATE notified SET pushed = 1 WHERE event_key = ?").bind(key).run();
  await pushTo(env, sender, devices, message(arrival, arrival.title || request.title));
  return "notified";
}
