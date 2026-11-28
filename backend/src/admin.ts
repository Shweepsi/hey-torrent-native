/**
 * Routes d'administration (`/v1/admin/…`), réservées au compte
 * administrateur : file des demandes et leur traitement, réglages des
 * serveurs chiffrés, accès des comptes. Le rôle est relu dans D1 à chaque appel,
 * pas seulement dans le jeton.
 *
 * Rien de ce qui passe ici n'est journalisé : ni les réglages (adresses,
 * clés), ni les noms de releases.
 */
import { findUser, type AccessStatus, type User } from "./accounts";
import { devicesOfUsers, pushTo } from "./availability";
import { decryptText, encryptText } from "./crypto";
import type { Env } from "./env";
import { ApiError, json, noContent, optionalString, readJson, requiredString } from "./http";
import type { PushSender } from "./push";
import { authenticate } from "./sessions";
import type { MediaType, RequestStatus } from "./watchlist";

export const ADMIN_PREFIX = "/v1/admin/";

/** Taille maximale des réglages chiffrés (texte JSON produit par le cœur). */
const MAX_SETTINGS = 12_000;
const MAX_REASON = 200;
const MAX_RELEASE_TITLE = 300;
/** Demandes renvoyées au plus par la file. */
const MAX_REQUESTS = 500;
/** Comptes renvoyés au plus par la liste des accès. */
const MAX_MEMBERS = 300;
const MAX_ALLOWED_EMAILS = 200;

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

/**
 * `POST …/available` : déjà sur Plex (arrivé avant les webhooks, ou saison que
 * les webhooks n'ont pas vue). Personne n'est prévenu ; la release envoyée,
 * s'il y en a une, est gardée.
 */
async function available(request: Request, env: Env, now: number, id: string): Promise<Response> {
  await requireAdmin(request, env, now);
  const result = await env.DB.prepare(
    `UPDATE requests SET status = 'available', refusal_reason = NULL, handled_at = ?, updated_at = ?
     WHERE id = ? AND status IN ('requested', 'in_progress', 'refused')`,
  )
    .bind(now, now, id)
    .run();
  if (result.meta.changes !== 1) {
    const exists = await env.DB.prepare("SELECT 1 FROM requests WHERE id = ?").bind(id).first();
    throw new ApiError(exists ? "conflict" : "not_found");
  }
  return json({ request: await oneRequest(env, id) });
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

// ── Accès des comptes ────────────────────────────────────────────

interface MemberRow {
  id: string;
  role: string;
  display_name: string | null;
  status: AccessStatus;
  /** Adresse vérifiée d'Apple ou de Google (la plus ancienne), pour reconnaître la personne. */
  email: string | null;
  created_at: number;
  status_changed_at: number | null;
}

/**
 * `GET /v1/admin/members` : les comptes, ceux en attente d'abord (les plus
 * anciens en tête), puis les autres, les plus récents d'abord.
 */
async function listMembers(request: Request, env: Env, now: number): Promise<Response> {
  await requireAdmin(request, env, now);
  const rows = await env.DB.prepare(
    `SELECT u.id, u.role, u.display_name, u.status, u.created_at, u.status_changed_at,
       (SELECT i.email FROM identities i WHERE i.user_id = u.id AND i.email IS NOT NULL
        ORDER BY i.created_at LIMIT 1) AS email
     FROM users u
     ORDER BY CASE u.status WHEN 'pending' THEN 0 ELSE 1 END,
       CASE u.status WHEN 'pending' THEN u.created_at ELSE -u.created_at END
     LIMIT ${MAX_MEMBERS}`,
  ).all<MemberRow>();
  return json({ members: rows.results });
}

/** Prévient ces personnes que leur accès est accepté. Jamais bloquant. */
async function announceGranted(env: Env, push: PushSender, userIds: string[], now: number): Promise<void> {
  if (userIds.length === 0) return;
  try {
    const devices = await devicesOfUsers(env, userIds, now);
    await pushTo(env, push, devices, {
      title: "HeyWatchlist",
      bodyKey: "push_access_granted",
      bodyArgs: [],
      collapseKey: "access",
      mediaType: "movie",
      tmdbId: 0,
    });
  } catch (error) {
    console.warn("access granted push failed", error instanceof Error ? error.name : typeof error);
  }
}

/**
 * `POST /v1/admin/members/{id}/approve` ou `…/refuse` : accepte, refuse ou
 * retire l'accès d'un compte. Un administrateur ne change pas d'état.
 */
async function decide(
  request: Request,
  env: Env,
  now: number,
  push: PushSender,
  id: string,
  status: "active" | "refused",
): Promise<Response> {
  await requireAdmin(request, env, now);
  const before = await env.DB.prepare("SELECT role, status FROM users WHERE id = ?")
    .bind(id)
    .first<{ role: string; status: AccessStatus }>();
  if (!before) throw new ApiError("not_found");
  if (before.role === "admin") throw new ApiError("conflict");
  if (before.status !== status) {
    await env.DB.prepare("UPDATE users SET status = ?, status_changed_at = ?, updated_at = ? WHERE id = ? AND role = 'member'")
      .bind(status, now, now, id)
      .run();
    if (status === "active") await announceGranted(env, push, [id], now);
  }
  return noContent();
}

// ── Adresses acceptées d'avance ──────────────────────────────────

/** Adresse saisie, en minuscules ; `invalid_request` si elle n'en a pas la forme. */
function emailOf(body: Record<string, unknown>): string {
  const email = requiredString(body, "email", 254).toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new ApiError("invalid_request");
  return email;
}

/** `GET /v1/admin/allowed-emails` */
async function listAllowedEmails(request: Request, env: Env, now: number): Promise<Response> {
  await requireAdmin(request, env, now);
  const rows = await env.DB.prepare(
    `SELECT email, created_at FROM allowed_emails ORDER BY created_at DESC LIMIT ${MAX_ALLOWED_EMAILS}`,
  ).all();
  return json({ emails: rows.results });
}

/**
 * `POST /v1/admin/allowed-emails` `{email}` : le compte qui se connectera avec
 * cette adresse vérifiée sera actif d'emblée. Un compte déjà en attente avec
 * cette adresse est accepté tout de suite.
 */
async function allowEmail(request: Request, env: Env, now: number, push: PushSender): Promise<Response> {
  await requireAdmin(request, env, now);
  const email = emailOf(await readJson(request));
  const waiting = await env.DB.prepare(
    `SELECT DISTINCT u.id FROM users u JOIN identities i ON i.user_id = u.id
     WHERE i.email = ? AND u.status = 'pending'`,
  )
    .bind(email)
    .all<{ id: string }>();
  const ids = waiting.results.map((row) => row.id);
  await env.DB.batch([
    env.DB.prepare("INSERT OR IGNORE INTO allowed_emails (email, created_at) VALUES (?, ?)").bind(email, now),
    env.DB.prepare(
      `UPDATE users SET status = 'active', status_changed_at = ?1, updated_at = ?1
       WHERE status = 'pending' AND id IN (SELECT value FROM json_each(?2))`,
    ).bind(now, JSON.stringify(ids)),
  ]);
  await announceGranted(env, push, ids, now);
  return json({ email, approved: ids.length }, 201);
}

/** `DELETE /v1/admin/allowed-emails` `{email}` : les comptes déjà créés gardent leur accès. */
async function removeAllowedEmail(request: Request, env: Env, now: number): Promise<Response> {
  await requireAdmin(request, env, now);
  const email = emailOf(await readJson(request));
  await env.DB.prepare("DELETE FROM allowed_emails WHERE email = ?").bind(email).run();
  return noContent();
}

// ── Aiguillage ───────────────────────────────────────────────────

type AdminHandler = (request: Request, env: Env, now: number, push: PushSender) => Promise<Response>;

/** Identifiant tel que le Worker les crée (UUID). */
const ID = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
const REQUEST_ACTION = new RegExp(`^/v1/admin/requests/${ID}/(start|refuse|reopen|available)$`);
const MEMBER_ACTION = new RegExp(`^/v1/admin/members/${ID}/(approve|refuse)$`);

/** Route `/v1/admin/…` ; `undefined` si la méthode ou le chemin n'existe pas. */
export function adminHandler(method: string, pathname: string): AdminHandler | undefined {
  if (pathname === "/v1/admin/requests") return method === "GET" ? listRequests : undefined;
  if (pathname === "/v1/admin/settings") {
    if (method === "GET") return getSettings;
    if (method === "PUT") return putSettings;
    if (method === "DELETE") return deleteSettings;
    return undefined;
  }
  if (pathname === "/v1/admin/members") return method === "GET" ? listMembers : undefined;
  if (pathname === "/v1/admin/allowed-emails") {
    if (method === "GET") return listAllowedEmails;
    if (method === "POST") return allowEmail;
    if (method === "DELETE") return removeAllowedEmail;
    return undefined;
  }
  const action = REQUEST_ACTION.exec(pathname);
  if (action?.[1] && action[2] && method === "POST") {
    const id = action[1];
    const handlers = { start, refuse, reopen, available } as const;
    const handler = handlers[action[2] as keyof typeof handlers];
    return (request, env, now) => handler(request, env, now, id);
  }
  const member = MEMBER_ACTION.exec(pathname);
  if (member?.[1] && member[2] && method === "POST") {
    const id = member[1];
    const status = member[2] === "approve" ? "active" : "refused";
    return (request, env, now, push) => decide(request, env, now, push, id, status);
  }
  return undefined;
}
