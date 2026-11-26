/**
 * Routes d'administration (`/v1/admin/…`), réservées au compte
 * administrateur : file des demandes et leur traitement, réglages des
 * serveurs chiffrés, invitations. Le rôle est relu dans D1 à chaque appel,
 * pas seulement dans le jeton.
 *
 * Rien de ce qui passe ici n'est journalisé : ni les réglages (adresses,
 * clés), ni les noms de releases.
 */
import { decryptText, encryptText } from "./crypto";
import { findUser, type User } from "./accounts";
import type { Env } from "./env";
import { ApiError, json, noContent, optionalString, readJson, requiredString } from "./http";
import { authenticate } from "./sessions";
import type { MediaType, RequestStatus } from "./watchlist";

export const ADMIN_PREFIX = "/v1/admin/";

/** Taille maximale des réglages chiffrés (texte JSON produit par le cœur). */
const MAX_SETTINGS = 12_000;
const MAX_REASON = 200;
const MAX_RELEASE_TITLE = 300;
/** Demandes renvoyées au plus par la file. */
const MAX_REQUESTS = 500;
const MAX_INVITATIONS = 50;

const STATUSES: readonly RequestStatus[] = ["requested", "in_progress", "available", "refused"];

/** Compte de la requête, s'il est administrateur ; `forbidden` sinon. */
async function requireAdmin(request: Request, env: Env, now: number): Promise<User> {
  const claims = await authenticate(request, env, now);
  const user = await findUser(env, claims.userId);
  if (!user) throw new ApiError("unauthorized");
  if (user.role !== "admin") throw new ApiError("forbidden");
  return user;
}

// ── Demandes ─────────────────────────────────────────────────────

interface RequestRow {
  id: string;
  media_type: MediaType;
  tmdb_id: number;
  title: string;
  poster_path: string | null;
  year: number | null;
  status: RequestStatus;
  refusal_reason: string | null;
  release_title: string | null;
  torrent_hash: string | null;
  created_at: number;
  updated_at: number;
  handled_at: number | null;
}

interface FollowerRow {
  request_id: string;
  display_name: string | null;
  seasons: string | null;
  added_at: number;
}

export interface AdminFollower {
  display_name: string | null;
  /** Saisons voulues ; `null` : film ou série entière. */
  seasons: number[] | null;
  added_at: number;
}

export interface AdminRequest extends RequestRow {
  followers: AdminFollower[];
  /** Saisons à chercher : `null` si quelqu'un veut toute la série (ou un film). */
  seasons: number[] | null;
}

const REQUEST_COLUMNS = `id, media_type, tmdb_id, title, poster_path, year, status, refusal_reason,
  release_title, torrent_hash, created_at, updated_at, handled_at`;

function seasonsOf(raw: string | null): number[] | null {
  if (raw === null) return null;
  try {
    const value: unknown = JSON.parse(raw);
    return Array.isArray(value) && value.every((n) => typeof n === "number" && Number.isInteger(n))
      ? (value as number[])
      : null;
  } catch {
    return null;
  }
}

/** Périmètre d'une demande de série : toute la série si un suiveur la veut entière, sinon l'union des saisons. */
function scope(row: RequestRow, followers: AdminFollower[]): number[] | null {
  if (row.media_type === "movie" || followers.length === 0) return null;
  const union = new Set<number>();
  for (const follower of followers) {
    if (follower.seasons === null) return null;
    for (const season of follower.seasons) union.add(season);
  }
  return [...union].sort((a, b) => a - b);
}

/** Les demandes données, avec leurs suiveurs vivants (watchlists non retirées). */
async function withFollowers(env: Env, rows: RequestRow[]): Promise<AdminRequest[]> {
  if (rows.length === 0) return [];
  const followers = new Map<string, AdminFollower[]>();
  // Par paquets : D1 limite le nombre de paramètres d'une requête.
  for (let start = 0; start < rows.length; start += 90) {
    const ids = rows.slice(start, start + 90).map((row) => row.id);
    const result = await env.DB.prepare(
      `SELECT w.request_id, u.display_name, w.seasons, w.added_at
       FROM watchlist_items w JOIN users u ON u.id = w.user_id
       WHERE w.deleted_at IS NULL AND w.request_id IN (${ids.map(() => "?").join(", ")})
       ORDER BY w.added_at`,
    )
      .bind(...ids)
      .all<FollowerRow>();
    for (const follower of result.results) {
      const list = followers.get(follower.request_id) ?? [];
      list.push({ display_name: follower.display_name, seasons: seasonsOf(follower.seasons), added_at: follower.added_at });
      followers.set(follower.request_id, list);
    }
  }
  return rows.map((row) => {
    const list = followers.get(row.id) ?? [];
    return { ...row, followers: list, seasons: scope(row, list) };
  });
}

/**
 * `GET /v1/admin/requests?status=…` : la file. Sans paramètre, les demandes à
 * traiter (demandées puis en cours), les plus anciennes d'abord ; `status=all`
 * pour tout voir, les plus récemment changées d'abord.
 */
async function listRequests(request: Request, env: Env, now: number): Promise<Response> {
  await requireAdmin(request, env, now);
  const filter = new URL(request.url).searchParams.get("status");
  let statement: D1PreparedStatement;
  if (filter === null || filter === "open") {
    statement = env.DB.prepare(
      `SELECT ${REQUEST_COLUMNS} FROM requests WHERE status IN ('requested', 'in_progress')
       ORDER BY CASE status WHEN 'requested' THEN 0 ELSE 1 END, created_at LIMIT ${MAX_REQUESTS}`,
    );
  } else if (filter === "all") {
    statement = env.DB.prepare(`SELECT ${REQUEST_COLUMNS} FROM requests ORDER BY updated_at DESC LIMIT ${MAX_REQUESTS}`);
  } else if ((STATUSES as readonly string[]).includes(filter)) {
    statement = env.DB.prepare(
      `SELECT ${REQUEST_COLUMNS} FROM requests WHERE status = ? ORDER BY updated_at DESC LIMIT ${MAX_REQUESTS}`,
    ).bind(filter);
  } else {
    throw new ApiError("invalid_request");
  }
  const rows = await statement.all<RequestRow>();
  return json({ requests: await withFollowers(env, rows.results) });
}

async function oneRequest(env: Env, id: string): Promise<AdminRequest> {
  const row = await env.DB.prepare(`SELECT ${REQUEST_COLUMNS} FROM requests WHERE id = ?`).bind(id).first<RequestRow>();
  if (!row) throw new ApiError("not_found");
  const [withList] = await withFollowers(env, [row]);
  if (!withList) throw new ApiError("internal");
  return withList;
}

/**
 * Change l'état d'une demande si elle est dans un des états `from` ; sinon
 * `not_found` (disparue) ou `conflict` (changée entre-temps, par exemple
 * déjà disponible sur Plex).
 */
async function transition(
  env: Env,
  id: string,
  from: readonly RequestStatus[],
  set: { status: RequestStatus; refusal_reason: string | null; release_title: string | null; torrent_hash: string | null },
  now: number,
): Promise<Response> {
  const result = await env.DB.prepare(
    `UPDATE requests SET status = ?, refusal_reason = ?, release_title = ?, torrent_hash = ?, handled_at = ?, updated_at = ?
     WHERE id = ? AND status IN (${from.map(() => "?").join(", ")})`,
  )
    .bind(set.status, set.refusal_reason, set.release_title, set.torrent_hash, now, now, id, ...from)
    .run();
  if (result.meta.changes !== 1) {
    const exists = await env.DB.prepare("SELECT 1 FROM requests WHERE id = ?").bind(id).first();
    throw new ApiError(exists ? "conflict" : "not_found");
  }
  return json({ request: await oneRequest(env, id) });
}

/** `POST …/start` `{release_title, torrent_hash?}` : envoyée à qBittorrent, donc « en cours ». */
async function start(request: Request, env: Env, now: number, id: string): Promise<Response> {
  await requireAdmin(request, env, now);
  const body = await readJson(request);
  const releaseTitle = requiredString(body, "release_title", MAX_RELEASE_TITLE);
  const hash = optionalString(body, "torrent_hash", 40)?.toLowerCase();
  if (hash !== undefined && !/^[0-9a-f]{40}$/.test(hash)) throw new ApiError("invalid_request");
  // Renvoyer une autre release d'une demande en cours est permis (la
  // première ne venait pas, par exemple).
  return transition(
    env,
    id,
    ["requested", "in_progress", "refused"],
    { status: "in_progress", refusal_reason: null, release_title: releaseTitle, torrent_hash: hash ?? null },
    now,
  );
}

/** `POST …/refuse` `{reason?}` : refusée, avec un motif court facultatif, montré aux suiveurs. */
async function refuse(request: Request, env: Env, now: number, id: string): Promise<Response> {
  await requireAdmin(request, env, now);
  const reason = optionalString(await readJson(request), "reason", MAX_REASON) ?? null;
  return transition(
    env,
    id,
    ["requested", "in_progress"],
    { status: "refused", refusal_reason: reason, release_title: null, torrent_hash: null },
    now,
  );
}

/** `POST …/reopen` : retour à « demandée » (annuler un refus ou un envoi). */
async function reopen(request: Request, env: Env, now: number, id: string): Promise<Response> {
  await requireAdmin(request, env, now);
  return transition(
    env,
    id,
    ["in_progress", "refused"],
    { status: "requested", refusal_reason: null, release_title: null, torrent_hash: null },
    now,
  );
}

// ── Réglages des serveurs ────────────────────────────────────────

/** Données associées au chiffrement : un texte chiffré ne se recolle pas sur un autre compte. */
const aad = (userId: string) => `heywatchlist-credentials:${userId}`;

/** `GET /v1/admin/settings` : les réglages déchiffrés, ou `null` s'il n'y en a pas. */
async function getSettings(request: Request, env: Env, now: number): Promise<Response> {
  const user = await requireAdmin(request, env, now);
  const row = await env.DB.prepare("SELECT iv, ciphertext, updated_at FROM credentials WHERE user_id = ?")
    .bind(user.id)
    .first<{ iv: string; ciphertext: string; updated_at: number }>();
  if (!row) return json({ settings: null, updated_at: null });
  const settings = await decryptText(env, row.iv, row.ciphertext, aad(user.id));
  return json({ settings, updated_at: row.updated_at });
}

/** `PUT /v1/admin/settings` `{settings}` : texte opaque produit par le cœur, chiffré avant d'être gardé. */
async function putSettings(request: Request, env: Env, now: number): Promise<Response> {
  const user = await requireAdmin(request, env, now);
  const settings = requiredString(await readJson(request), "settings", MAX_SETTINGS);
  const sealed = await encryptText(env, settings, aad(user.id));
  await env.DB.prepare(
    `INSERT INTO credentials (user_id, iv, ciphertext, updated_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (user_id) DO UPDATE SET iv = excluded.iv, ciphertext = excluded.ciphertext, updated_at = excluded.updated_at`,
  )
    .bind(user.id, sealed.iv, sealed.ciphertext, now)
    .run();
  return json({ updated_at: now });
}

async function deleteSettings(request: Request, env: Env, now: number): Promise<Response> {
  const user = await requireAdmin(request, env, now);
  await env.DB.prepare("DELETE FROM credentials WHERE user_id = ?").bind(user.id).run();
  return noContent();
}

// ── Invitations ──────────────────────────────────────────────────

/** `GET /v1/admin/invitations` : les dernières invitations, avec qui les a utilisées. */
async function listInvitations(request: Request, env: Env, now: number): Promise<Response> {
  await requireAdmin(request, env, now);
  const rows = await env.DB.prepare(
    `SELECT i.id, i.created_at, i.expires_at, i.used_at, i.revoked_at, u.display_name AS used_by_name
     FROM invitations i LEFT JOIN users u ON u.id = i.used_by
     ORDER BY i.created_at DESC LIMIT ${MAX_INVITATIONS}`,
  ).all();
  return json({ invitations: rows.results });
}

/** `DELETE /v1/admin/invitations/{id}` : révoque une invitation pas encore utilisée. */
async function revokeInvitation(request: Request, env: Env, now: number, id: string): Promise<Response> {
  await requireAdmin(request, env, now);
  const result = await env.DB.prepare(
    "UPDATE invitations SET revoked_at = ? WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL",
  )
    .bind(now, id)
    .run();
  if (result.meta.changes !== 1) {
    const exists = await env.DB.prepare("SELECT 1 FROM invitations WHERE id = ?").bind(id).first();
    throw new ApiError(exists ? "conflict" : "not_found");
  }
  return noContent();
}

// ── Aiguillage ───────────────────────────────────────────────────

type AdminHandler = (request: Request, env: Env, now: number) => Promise<Response>;

/** Identifiant tel que le Worker les crée (UUID). */
const ID = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
const REQUEST_ACTION = new RegExp(`^/v1/admin/requests/${ID}/(start|refuse|reopen)$`);
const INVITATION = new RegExp(`^/v1/admin/invitations/${ID}$`);

/** Route `/v1/admin/…` ; `undefined` si la méthode ou le chemin n'existe pas. */
export function adminHandler(method: string, pathname: string): AdminHandler | undefined {
  if (pathname === "/v1/admin/requests") return method === "GET" ? listRequests : undefined;
  if (pathname === "/v1/admin/settings") {
    if (method === "GET") return getSettings;
    if (method === "PUT") return putSettings;
    if (method === "DELETE") return deleteSettings;
    return undefined;
  }
  if (pathname === "/v1/admin/invitations") return method === "GET" ? listInvitations : undefined;
  const action = REQUEST_ACTION.exec(pathname);
  if (action?.[1] && action[2] && method === "POST") {
    const id = action[1];
    const handlers = { start, refuse, reopen } as const;
    const handler = handlers[action[2] as keyof typeof handlers];
    return (request, env, now) => handler(request, env, now, id);
  }
  const invitation = INVITATION.exec(pathname);
  if (invitation?.[1] && method === "DELETE") {
    const id = invitation[1];
    return (request, env, now) => revokeInvitation(request, env, now, id);
  }
  return undefined;
}
