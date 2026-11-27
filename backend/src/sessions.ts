/**
 * Jetons de l'app : jeton d'accès court (JWT HS256, 15 minutes) et jeton de
 * rafraîchissement opaque (haché dans D1, renouvelé à chaque usage, révocable).
 */
import { SignJWT, jwtVerify } from "jose";
import { fromBase64, randomToken, sha256Hex } from "./crypto";
import type { Env } from "./env";
import { ApiError } from "./http";

export const ACCESS_TTL_SECONDS = 15 * 60;
/** Une session inutilisée pendant 90 jours expire ; chaque usage la prolonge. */
export const SESSION_TTL_SECONDS = 90 * 24 * 3600;

const ISSUER = "heywatchlist-api";
const AUDIENCE = "heywatchlist-app";

export type Role = "admin" | "member";

export interface AccessClaims {
  userId: string;
  sessionId: string;
  role: Role;
}

export interface TokenPair {
  access_token: string;
  access_expires_in: number;
  refresh_token: string;
}

function signingKey(env: Env): Uint8Array {
  const key = fromBase64(env.SESSION_SIGNING_KEY);
  if (key.length < 32) throw new ApiError("internal");
  return key;
}

async function accessToken(env: Env, claims: AccessClaims, now: number): Promise<string> {
  return new SignJWT({ sid: claims.sessionId, role: claims.role })
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setSubject(claims.userId)
    .setIssuedAt(now)
    .setExpirationTime(now + ACCESS_TTL_SECONDS)
    .sign(signingKey(env));
}

/**
 * Jeton d'accès de l'en-tête `Authorization: Bearer …`, ou `unauthorized`,
 * quel que soit l'état du compte : seulement pour les routes permises à un
 * compte en attente (profil, suppression, appareil, amorçage).
 */
export async function authenticateSession(request: Request, env: Env, now: number): Promise<AccessClaims> {
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9._-]+)$/.exec(header);
  if (!match?.[1]) throw new ApiError("unauthorized");
  try {
    const { payload } = await jwtVerify(match[1], signingKey(env), {
      issuer: ISSUER,
      audience: AUDIENCE,
      algorithms: ["HS256"],
      currentDate: new Date(now * 1000),
    });
    const role = payload.role;
    if (typeof payload.sub !== "string" || typeof payload.sid !== "string" || (role !== "admin" && role !== "member")) {
      throw new ApiError("unauthorized");
    }
    return { userId: payload.sub, sessionId: payload.sid, role };
  } catch {
    throw new ApiError("unauthorized");
  }
}

/**
 * Comme `authenticateSession`, pour un compte actif seulement : l'état est relu
 * dans D1 à chaque appel, pour qu'une acceptation ou un refus vaille aussitôt.
 * `access_pending` ou `access_refused` sinon ; `unauthorized` si le compte
 * n'existe plus.
 */
export async function authenticate(request: Request, env: Env, now: number): Promise<AccessClaims> {
  const claims = await authenticateSession(request, env, now);
  requireActive(await statusOf(env, claims.userId));
  return claims;
}

/**
 * Pour le relais TMDB, qui doit survivre à une panne de D1 : si l'état ne
 * peut pas être relu, le jeton signé suffit (le catalogue TMDB est public).
 */
export async function authenticateCatalog(request: Request, env: Env, now: number): Promise<AccessClaims> {
  const claims = await authenticateSession(request, env, now);
  let status: string | null;
  try {
    status = await statusOf(env, claims.userId);
  } catch {
    return claims;
  }
  requireActive(status);
  return claims;
}

async function statusOf(env: Env, userId: string): Promise<string | null> {
  const row = await env.DB.prepare("SELECT status FROM users WHERE id = ?").bind(userId).first<{ status: string }>();
  return row?.status ?? null;
}

function requireActive(status: string | null): void {
  if (status === null) throw new ApiError("unauthorized");
  if (status === "pending") throw new ApiError("access_pending");
  if (status !== "active") throw new ApiError("access_refused");
}

/** Ouvre une session pour ce compte et renvoie ses premiers jetons. */
export async function openSession(env: Env, userId: string, role: Role, now: number): Promise<TokenPair> {
  const sessionId = crypto.randomUUID();
  const refresh = randomToken();
  await env.DB.prepare(
    `INSERT INTO sessions (id, user_id, refresh_hash, created_at, last_used_at, expires_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  )
    .bind(sessionId, userId, await sha256Hex(refresh), now, now, now + SESSION_TTL_SECONDS)
    .run();
  return {
    access_token: await accessToken(env, { userId, sessionId, role }, now),
    access_expires_in: ACCESS_TTL_SECONDS,
    refresh_token: refresh,
  };
}

interface SessionRow {
  id: string;
  user_id: string;
  role: Role;
  refresh_hash: string;
  expires_at: number;
  revoked_at: number | null;
}

/**
 * Échange un jeton de rafraîchissement contre une nouvelle paire. Un ancien
 * jeton présenté de nouveau signe un vol : la session est coupée.
 */
export async function refreshSession(env: Env, refresh: string, now: number): Promise<TokenPair> {
  const hash = await sha256Hex(refresh);
  const row = await env.DB.prepare(
    `SELECT s.id, s.user_id, u.role, s.refresh_hash, s.expires_at, s.revoked_at
     FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.refresh_hash = ?1 OR s.previous_hash = ?1`,
  )
    .bind(hash)
    .first<SessionRow>();

  if (!row || row.revoked_at !== null || row.expires_at <= now) throw new ApiError("session_expired");
  if (row.refresh_hash !== hash) {
    await env.DB.prepare("UPDATE sessions SET revoked_at = ? WHERE id = ?").bind(now, row.id).run();
    throw new ApiError("session_expired");
  }

  const next = randomToken();
  // La condition sur l'ancien condensé rend l'échange atomique : deux
  // rafraîchissements simultanés ne peuvent pas réussir tous les deux.
  const result = await env.DB.prepare(
    `UPDATE sessions SET refresh_hash = ?, previous_hash = ?, last_used_at = ?, expires_at = ?
     WHERE id = ? AND refresh_hash = ? AND revoked_at IS NULL`,
  )
    .bind(await sha256Hex(next), hash, now, now + SESSION_TTL_SECONDS, row.id, hash)
    .run();
  if (result.meta.changes !== 1) throw new ApiError("session_expired");

  return {
    access_token: await accessToken(env, { userId: row.user_id, sessionId: row.id, role: row.role }, now),
    access_expires_in: ACCESS_TTL_SECONDS,
    refresh_token: next,
  };
}

/** Ferme la session de ce jeton ; sans effet s'il est inconnu. */
export async function revokeSession(env: Env, refresh: string, now: number): Promise<void> {
  const hash = await sha256Hex(refresh);
  await env.DB.prepare("UPDATE sessions SET revoked_at = ? WHERE refresh_hash = ? AND revoked_at IS NULL")
    .bind(now, hash)
    .run();
}
