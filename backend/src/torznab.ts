/**
 * Lecture des sources Torznab de l'administrateur par le Worker, pour la
 * recherche automatique. Version réduite du client du cœur
 * (`core/src/admin/torznab.rs`) : seuls les noms, tailles et sources
 * comptent, Claude juge la pertinence. Les liens ne sont ni lus ni gardés ;
 * le choix et l'envoi restent dans l'app.
 *
 * Lecture tolérante : un élément illisible est ignoré. Ni l'adresse ni la
 * clé d'une source ne sont journalisées.
 */
import type { FetchLike } from "./apple";

/** Délai maximal d'une source, comme dans le cœur. */
export const SOURCE_TIMEOUT_MS = 6_000;
const MAX_BODY = 8 * 1024 * 1024;

export interface TorznabSource {
  id: string;
  name: string;
  url: string;
  api_key: string;
  enabled: boolean;
}

/** Raison d'un échec, mêmes valeurs que `SourceFailure` du cœur (en snake_case). */
export type SourceFailure =
  | "invalid_address"
  | "unreachable"
  | "timeout"
  | "auth_failed"
  | "rate_limited"
  | "server"
  | "invalid_response";

export interface FeedItem {
  title: string;
  size_bytes?: number | undefined;
  seeders?: number | undefined;
  indexer?: string | undefined;
}

export type Mode = { kind: "movie" } | { kind: "tv"; season?: number | undefined } | { kind: "generic" };

class FeedError extends Error {
  constructor(readonly failure: SourceFailure | "unsupported") {
    super(failure);
  }
}

/** Adresse de l'API : le chemin finit par `/api`, ajouté s'il manque. */
export function apiUrl(source: TorznabSource): URL {
  let url: URL;
  try {
    url = new URL(source.url.trim());
  } catch {
    throw new FeedError("invalid_address");
  }
  if (url.protocol !== "https:") throw new FeedError("invalid_address");
  const segments = url.pathname.split("/").filter((s) => s.length > 0);
  if (segments.at(-1) !== "api") url.pathname = `/${[...segments, "api"].join("/")}`;
  return url;
}

export function searchUrl(source: TorznabSource, mode: Mode, text: string): URL {
  const url = apiUrl(source);
  const params = url.searchParams;
  if (mode.kind === "movie") {
    params.append("t", "movie");
    params.append("cat", "2000");
  } else if (mode.kind === "tv") {
    params.append("t", "tvsearch");
    params.append("cat", "5000");
    if (mode.season !== undefined) params.append("season", String(mode.season));
  } else {
    params.append("t", "search");
  }
  params.append("q", text);
  params.append("apikey", source.api_key.trim());
  params.append("extended", "1");
  return url;
}

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

/** Texte d'un nœud XML : CDATA et entités décodés. */
function decode(raw: string): string {
  const cdata = /^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/.exec(raw);
  if (cdata?.[1] !== undefined) return cdata[1].trim();
  return raw
    .replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, (whole, code: string) => {
      if (code.startsWith("#x") || code.startsWith("#X")) return String.fromCodePoint(parseInt(code.slice(2), 16));
      if (code.startsWith("#")) return String.fromCodePoint(parseInt(code.slice(1), 10));
      return ENTITIES[code.toLowerCase()] ?? whole;
    })
    .trim();
}

/** Texte du premier élément `name` (sans préfixe d'espace de noms) d'un bloc. */
function child(block: string, name: string): string | undefined {
  const match = new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`, "i").exec(block);
  const text = match?.[1] !== undefined ? decode(match[1]) : "";
  return text.length > 0 ? text : undefined;
}

function attribute(tag: string, name: string): string | undefined {
  const match = new RegExp(`\\s${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, "i").exec(tag);
  const value = match?.[2] ?? match?.[3];
  return value === undefined ? undefined : decode(value);
}

function count(value: string | undefined): number | undefined {
  if (value === undefined || !/^[0-9]+$/.test(value.trim())) return undefined;
  const n = Number(value.trim());
  return Number.isSafeInteger(n) ? n : undefined;
}

function errorOf(code: number): FeedError {
  if (code >= 100 && code <= 102) return new FeedError("auth_failed");
  if (code >= 201 && code <= 203) return new FeedError("unsupported");
  if (code === 429) return new FeedError("rate_limited");
  if (code >= 900 && code <= 999) return new FeedError("server");
  return new FeedError("invalid_response");
}

/** Lit un flux Torznab ; lève une `FeedError` sur une erreur Torznab ou un document illisible. */
export function parseFeed(body: string): FeedItem[] {
  const root = /<(rss|error)\b([^>]*)>?/i.exec(body);
  if (!root) throw new FeedError("invalid_response");
  if (root[1]?.toLowerCase() === "error") throw errorOf(count(attribute(root[2] ?? "", "code")) ?? 0);
  const items: FeedItem[] = [];
  for (const match of body.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)) {
    const block = match[1] ?? "";
    const title = child(block, "title");
    if (!title) continue;
    const attrs = new Map<string, string>();
    for (const tag of block.matchAll(/<(?:[a-z]+:)?attr\b[^>]*>/gi)) {
      const name = attribute(tag[0], "name")?.toLowerCase();
      const value = attribute(tag[0], "value");
      if (name && value !== undefined && !attrs.has(name)) attrs.set(name, value);
    }
    const enclosure = /<enclosure\b[^>]*>/i.exec(block)?.[0];
    const size = count(child(block, "size")) ?? count(attrs.get("size")) ?? (enclosure ? count(attribute(enclosure, "length")) : undefined);
    items.push({
      title: title.slice(0, 300),
      size_bytes: size !== undefined && size > 0 ? size : undefined,
      seeders: count(attrs.get("seeders")),
      indexer: child(block, "prowlarrindexer") ?? child(block, "jackettindexer") ?? child(block, "indexer"),
    });
  }
  return items;
}

async function fetchFeed(fetcher: FetchLike, url: URL): Promise<FeedItem[]> {
  let response: Response;
  try {
    response = await fetcher(url.toString(), { signal: AbortSignal.timeout(SOURCE_TIMEOUT_MS) });
  } catch (error) {
    throw new FeedError(error instanceof Error && error.name === "TimeoutError" ? "timeout" : "unreachable");
  }
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw new FeedError("unreachable");
  }
  if (text.length > MAX_BODY) throw new FeedError("invalid_response");
  if (response.ok) return parseFeed(text);
  // Une erreur Torznab dans un statut d'échec dit mieux la cause.
  try {
    parseFeed(text);
  } catch (error) {
    if (error instanceof FeedError && error.failure !== "invalid_response") throw error;
  }
  if (response.status === 401 || response.status === 403) throw new FeedError("auth_failed");
  if (response.status === 429) throw new FeedError("rate_limited");
  if (response.status >= 500) throw new FeedError("server");
  throw new FeedError("invalid_response");
}

async function searchText(fetcher: FetchLike, source: TorznabSource, mode: Mode, text: string): Promise<FeedItem[]> {
  try {
    return await fetchFeed(fetcher, searchUrl(source, mode, text));
  } catch (error) {
    if (error instanceof FeedError && error.failure === "unsupported") {
      return fetchFeed(fetcher, searchUrl(source, { kind: "generic" }, text));
    }
    throw error;
  }
}

/** Résultat d'une source : ses éléments, ou la raison de son échec. */
export type SourceResult = { source: TorznabSource; items: FeedItem[]; failure: SourceFailure | null };

/**
 * Cherche chaque texte sur une source. Échoue seulement si tous les textes
 * échouent ; un `unsupported` après repli compte comme réponse illisible.
 */
export async function searchSource(fetcher: FetchLike, source: TorznabSource, mode: Mode, texts: string[]): Promise<SourceResult> {
  const results = await Promise.allSettled(texts.map((text) => searchText(fetcher, source, mode, text)));
  const items: FeedItem[] = [];
  let failure: SourceFailure | null = null;
  for (const result of results) {
    if (result.status === "fulfilled") items.push(...result.value);
    else if (failure === null) {
      const reason = result.reason;
      failure =
        reason instanceof FeedError && reason.failure !== "unsupported" ? reason.failure : "invalid_response";
    }
  }
  return { source, items, failure: items.length > 0 ? null : failure };
}

/** Le mot `term` apparaît-il entier (sans casse) dans `title` ? */
export function containsWord(title: string, term: string): boolean {
  const words = (text: string) => text.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 0);
  const needle = words(term);
  if (needle.length === 0) return false;
  const hay = words(title);
  for (let i = 0; i + needle.length <= hay.length; i++) {
    if (needle.every((w, j) => hay[i + j] === w)) return true;
  }
  return false;
}
