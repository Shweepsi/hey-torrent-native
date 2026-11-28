/**
 * Connexion directe à Plex Media Server (adresse HTTPS + jeton de
 * l'administrateur), en plus des webhooks : le Worker lit la bibliothèque
 * toutes les 6 h, note ce qui s'y trouve (`library`) et passe « disponible »
 * les demandes déjà servies, par le même chemin qu'une arrivée de webhook
 * (`processArrival` : dédoublonnage, saisons complètes, push). Une série
 * compte par saisons complètes ; les épisodes isolés restent aux webhooks.
 *
 * Ni l'adresse ni le jeton ne sont journalisés.
 */
import type { FetchLike } from "./apple";
import type { PlexConnection } from "./adminsettings";
import { processArrival } from "./availability";
import type { Env } from "./env";
import { ApiError } from "./http";
import type { PushSender } from "./push";
import { airedEpisodesOf } from "./seasons";
import { plexTmdbId } from "./webhooks";
import type { MediaType } from "./watchlist";

const TIMEOUT_MS = 15_000;
/** Épisodes lus au plus par série demandée. */
const MAX_EPISODES = 2_000;

export type PlexStatus = "ok" | "unreachable" | "auth_failed" | "invalid_response";

class PlexError extends Error {
  constructor(readonly status: Exclude<PlexStatus, "ok">) {
    super(status);
  }
}

function base(connection: PlexConnection): URL {
  let url: URL;
  try {
    url = new URL(connection.url.trim());
  } catch {
    throw new PlexError("unreachable");
  }
  if (url.protocol !== "https:") throw new PlexError("unreachable");
  return url;
}

/** `GET` d'un chemin de l'API Plex, en JSON ; rend `MediaContainer`. */
async function get(fetcher: FetchLike, connection: PlexConnection, path: string, query: Record<string, string> = {}) {
  const url = base(connection);
  url.pathname = `${url.pathname.replace(/\/+$/, "")}${path}`;
  for (const [key, value] of Object.entries(query)) url.searchParams.set(key, value);
  let response: Response;
  try {
    response = await fetcher(url.toString(), {
      headers: { accept: "application/json", "x-plex-token": connection.token.trim() },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw new PlexError("unreachable");
  }
  if (response.status === 401 || response.status === 403) throw new PlexError("auth_failed");
  if (!response.ok) throw new PlexError("unreachable");
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new PlexError("invalid_response");
  }
  const container = (body as { MediaContainer?: unknown } | null)?.MediaContainer;
  if (typeof container !== "object" || container === null) throw new PlexError("invalid_response");
  return container as Record<string, unknown>;
}

const list = (container: Record<string, unknown>, key: string): Record<string, unknown>[] =>
  Array.isArray(container[key])
    ? (container[key] as unknown[]).filter((v): v is Record<string, unknown> => typeof v === "object" && v !== null)
    : [];

interface Section {
  key: string;
  mediaType: MediaType;
}

async function sections(fetcher: FetchLike, connection: PlexConnection): Promise<Section[]> {
  const container = await get(fetcher, connection, "/library/sections");
  return list(container, "Directory").flatMap((dir): Section[] => {
    const key = typeof dir.key === "string" || typeof dir.key === "number" ? String(dir.key) : "";
    if (!/^[0-9]+$/.test(key)) return [];
    if (dir.type === "movie") return [{ key, mediaType: "movie" }];
    if (dir.type === "show") return [{ key, mediaType: "tv" }];
    return [];
  });
}

/** Test depuis Serveurs : nombre de bibliothèques films et séries. */
export async function testPlex(connection: PlexConnection, fetcher: FetchLike): Promise<number> {
  try {
    return (await sections(fetcher, connection)).length;
  } catch (error) {
    if (error instanceof PlexError && error.status === "auth_failed") throw new ApiError("plex_auth_failed");
    throw new ApiError("plex_unreachable");
  }
}

interface LibraryItem {
  mediaType: MediaType;
  tmdbId: number;
  /** Identifiant Plex, pour lire les épisodes d'une série. */
  ratingKey: string;
  title: string;
}

async function libraryItems(fetcher: FetchLike, connection: PlexConnection): Promise<LibraryItem[]> {
  const items: LibraryItem[] = [];
  for (const section of await sections(fetcher, connection)) {
    const container = await get(fetcher, connection, `/library/sections/${section.key}/all`, { includeGuids: "1" });
    for (const metadata of list(container, "Metadata")) {
      // Tolérant : un élément sans identifiant TMDB est ignoré.
      const tmdbId = plexTmdbId(metadata);
      const ratingKey = typeof metadata.ratingKey === "string" || typeof metadata.ratingKey === "number" ? String(metadata.ratingKey) : "";
      if (tmdbId === undefined || !/^[0-9]+$/.test(ratingKey)) continue;
      items.push({
        mediaType: section.mediaType,
        tmdbId,
        ratingKey,
        title: typeof metadata.title === "string" ? metadata.title : "",
      });
    }
  }
  return items;
}

async function episodesOf(fetcher: FetchLike, connection: PlexConnection, ratingKey: string): Promise<{ season: number; episode: number }[]> {
  const container = await get(fetcher, connection, `/library/metadata/${ratingKey}/allLeaves`);
  const nonNegative = (v: unknown) => (typeof v === "number" && Number.isInteger(v) && v >= 0 ? v : undefined);
  return list(container, "Metadata")
    .slice(0, MAX_EPISODES)
    .flatMap((m) => {
      const season = nonNegative(m.parentIndex);
      const episode = nonNegative(m.index);
      return season === undefined || episode === undefined ? [] : [{ season, episode }];
    });
}

export interface PlexSync {
  status: PlexStatus;
  /** Films et séries reconnus dans la bibliothèque. */
  items: number;
}

/**
 * Lit la bibliothèque : `library` à jour, puis chaque demande en attente ou en
 * cours déjà présente sur Plex suit le chemin d'une arrivée de webhook.
 */
export async function syncPlex(
  env: Env,
  fetcher: FetchLike,
  sender: PushSender,
  connection: PlexConnection,
  now: number,
): Promise<PlexSync> {
  let items: LibraryItem[];
  try {
    items = await libraryItems(fetcher, connection);
  } catch (error) {
    return { status: error instanceof PlexError ? error.status : "invalid_response", items: 0 };
  }
  for (let start = 0; start < items.length; start += 100) {
    await env.DB.batch(
      items.slice(start, start + 100).map((item) =>
        env.DB.prepare(
          `INSERT INTO library (media_type, tmdb_id, first_seen_at, last_seen_at) VALUES (?1, ?2, ?3, ?3)
           ON CONFLICT (media_type, tmdb_id) DO UPDATE SET last_seen_at = ?3`,
        ).bind(item.mediaType, item.tmdbId, now),
      ),
    );
  }
  const { results: open } = await env.DB.prepare(
    "SELECT media_type, tmdb_id FROM requests WHERE status IN ('requested', 'in_progress')",
  ).all<{ media_type: MediaType; tmdb_id: number }>();
  const wanted = new Set(open.map((r) => `${r.media_type}:${r.tmdb_id}`));
  for (const item of items) {
    if (!wanted.has(`${item.mediaType}:${item.tmdbId}`)) continue;
    try {
      if (item.mediaType === "movie") {
        await processArrival(env, fetcher, sender, { mediaType: "movie", tmdbId: item.tmdbId, title: item.title }, now, "library");
        continue;
      }
      // Une arrivée par saison complète (épisodes déjà diffusés, d'après
      // TMDB) : peu d'écritures, et une push par saison au plus.
      const aired = await airedEpisodesOf(env, fetcher, item.tmdbId, now);
      if (!aired) continue;
      const present = new Map<number, Set<number>>();
      for (const { season, episode } of await episodesOf(fetcher, connection, item.ratingKey)) {
        const set = present.get(season) ?? new Set<number>();
        set.add(episode);
        present.set(season, set);
      }
      for (const [season, episodes] of present) {
        const count = aired.get(season) ?? 0;
        if (count === 0 || episodes.size < count) continue;
        await processArrival(env, fetcher, sender, { mediaType: "tv", tmdbId: item.tmdbId, season, title: item.title }, now, "library");
      }
    } catch (error) {
      // Une série illisible n'arrête pas les autres.
      console.warn("plex item failed", error instanceof Error ? error.name : typeof error);
    }
  }
  return { status: "ok", items: items.length };
}
