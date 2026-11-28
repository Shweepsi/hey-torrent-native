/**
 * Saisons d'une série déjà sur Plex : une demande de série ne passe
 * « disponible » que quand chaque saison voulue y est en entier (épisodes déjà
 * diffusés), et se rouvre quand quelqu'un veut une saison qui n'y est pas.
 *
 * Ce qui est arrivé vient des arrivées notées par les webhooks (`notified`) ;
 * ce qui a été diffusé, de la fiche TMDB de la série. Les saisons spéciales
 * (saison 0) ne comptent que si quelqu'un les a choisies.
 */
import type { FetchLike } from "./apple";
import type { Env } from "./env";
import { cachedTmdb } from "./tmdb";

/** Épisodes déjà diffusés de chaque saison, d'après la fiche TMDB de la série. */
export type AiredEpisodes = Map<number, number>;

function nonNegative(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : undefined;
}

/** Lit une fiche TMDB de série (`tv/{id}`) ; saisons illisibles ignorées. */
export function airedEpisodes(root: Record<string, unknown>): AiredEpisodes {
  const last = root.last_episode_to_air as { season_number?: unknown; episode_number?: unknown } | null | undefined;
  const lastSeason = nonNegative(last?.season_number);
  const lastEpisode = nonNegative(last?.episode_number);
  const aired: AiredEpisodes = new Map();
  if (!Array.isArray(root.seasons)) return aired;
  for (const entry of root.seasons as unknown[]) {
    const season = entry as { season_number?: unknown; episode_count?: unknown } | null;
    const number = nonNegative(season?.season_number);
    const count = nonNegative(season?.episode_count);
    if (number === undefined || count === undefined) continue;
    // Sans dernier épisode diffusé connu : le nombre d'épisodes de la fiche.
    if (lastSeason === undefined || lastEpisode === undefined || number === 0) aired.set(number, count);
    else if (number < lastSeason) aired.set(number, count);
    else if (number === lastSeason) aired.set(number, Math.min(count, lastEpisode));
    else aired.set(number, 0);
  }
  return aired;
}

/** Fiche TMDB de la série, lue dans le cache du Worker ; `null` si TMDB ne répond pas. */
export async function airedEpisodesOf(env: Env, fetcher: FetchLike, tmdbId: number, now: number): Promise<AiredEpisodes | null> {
  try {
    const body = await cachedTmdb(env, fetcher, `tv/${tmdbId}`, new URLSearchParams(), now);
    const root: unknown = JSON.parse(body);
    return typeof root === "object" && root !== null ? airedEpisodes(root as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Ce que les webhooks ont vu arriver d'une série. */
interface Arrived {
  /** La série entière a été annoncée d'un coup. */
  whole: boolean;
  /** Saisons annoncées en entier. */
  seasons: Set<number>;
  /** Épisodes vus, par saison. */
  episodes: Map<number, Set<number>>;
}

const EVENT = /^tv:[0-9]+(?::s([0-9]+)(?:e([0-9]+))?)?$/;

async function arrivedOf(env: Env, tmdbId: number): Promise<Arrived> {
  const { results } = await env.DB.prepare("SELECT event_key FROM notified WHERE media_type = 'tv' AND tmdb_id = ?")
    .bind(tmdbId)
    .all<{ event_key: string }>();
  const arrived: Arrived = { whole: false, seasons: new Set(), episodes: new Map() };
  for (const { event_key: key } of results) {
    const match = EVENT.exec(key);
    if (!match) continue;
    if (match[1] === undefined) {
      arrived.whole = true;
      continue;
    }
    const season = Number(match[1]);
    if (match[2] === undefined) {
      arrived.seasons.add(season);
      continue;
    }
    const episodes = arrived.episodes.get(season) ?? new Set<number>();
    episodes.add(Number(match[2]));
    arrived.episodes.set(season, episodes);
  }
  return arrived;
}

/**
 * Saisons voulues qui ne sont pas encore en entier sur Plex. `wanted` :
 * `null` pour toute la série (chaque saison déjà commencée, spéciaux exclus).
 * Une saison choisie mais pas encore diffusée manque.
 */
export async function missingSeasons(
  env: Env,
  tmdbId: number,
  wanted: number[] | null,
  aired: AiredEpisodes,
): Promise<number[]> {
  const arrived = await arrivedOf(env, tmdbId);
  if (arrived.whole) return [];
  const seasons =
    wanted ??
    [...aired.entries()].filter(([season, count]) => season > 0 && count > 0).map(([season]) => season);
  return seasons.filter((season) => {
    if (arrived.seasons.has(season)) return false;
    const count = aired.get(season) ?? 0;
    return count === 0 || (arrived.episodes.get(season)?.size ?? 0) < count;
  });
}

/** Saisons voulues par les suiveurs vivants d'une demande ; `null` : toute la série. */
export async function wantedSeasons(env: Env, requestId: string): Promise<number[] | null> {
  const { results } = await env.DB.prepare(
    "SELECT seasons FROM watchlist_items WHERE request_id = ? AND deleted_at IS NULL",
  )
    .bind(requestId)
    .all<{ seasons: string | null }>();
  if (results.length === 0) return null;
  const union = new Set<number>();
  for (const { seasons } of results) {
    if (seasons === null) return null;
    let value: unknown;
    try {
      value = JSON.parse(seasons);
    } catch {
      return null;
    }
    if (!Array.isArray(value)) return null;
    for (const season of value) if (typeof season === "number" && Number.isInteger(season)) union.add(season);
  }
  return [...union].sort((a, b) => a - b);
}
