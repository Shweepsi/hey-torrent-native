/**
 * Comptes sur validation : retrouver, rattacher ou créer le compte d'une
 * identité Apple ou Google vérifiée. Un nouveau compte attend que
 * l'administrateur l'accepte, sauf si son adresse est acceptée d'avance.
 */
import { normalizeCode, safeEqual, sha256Hex } from "./crypto";
import type { Env } from "./env";
import { ApiError } from "./http";
import type { VerifiedIdentity } from "./providers";
import type { Role } from "./sessions";

/** `pending` : en attente de l'administrateur ; `refused` : accès refusé ou retiré. */
export type AccessStatus = "active" | "pending" | "refused";

export interface User {
  id: string;
  role: Role;
  display_name: string | null;
  status: AccessStatus;
}

export interface PublicUser extends User {
  /** Fournisseurs rattachés : l'app iPhone sait ainsi s'il faut révoquer Apple. */
  providers: string[];
}

const USER_COLUMNS = "u.id, u.role, u.display_name, u.status";

/** Ce que l'API renvoie d'un compte. */
export async function publicUser(env: Env, user: User): Promise<PublicUser> {
  const rows = await env.DB.prepare("SELECT provider FROM identities WHERE user_id = ? ORDER BY provider")
    .bind(user.id)
    .all<{ provider: string }>();
  return {
    id: user.id,
    role: user.role,
    display_name: user.display_name,
    status: user.status,
    providers: rows.results.map((row) => row.provider),
  };
}

export async function findUser(env: Env, userId: string): Promise<User | null> {
  return env.DB.prepare(`SELECT ${USER_COLUMNS} FROM users u WHERE u.id = ?`).bind(userId).first<User>();
}

async function userByIdentity(env: Env, identity: VerifiedIdentity): Promise<User | null> {
  return env.DB.prepare(
    `SELECT ${USER_COLUMNS} FROM identities i JOIN users u ON u.id = i.user_id
     WHERE i.provider = ? AND i.subject = ?`,
  )
    .bind(identity.provider, identity.subject)
    .first<User>();
}

/** Compte qui a déjà une identité vérifiée avec la même adresse e-mail. */
async function userByEmail(env: Env, email: string): Promise<User | null> {
  return env.DB.prepare(
    `SELECT ${USER_COLUMNS} FROM identities i JOIN users u ON u.id = i.user_id
     WHERE i.email = ? ORDER BY i.created_at LIMIT 1`,
  )
    .bind(email)
    .first<User>();
}

function identityInsert(env: Env, identity: VerifiedIdentity, userId: string, now: number): D1PreparedStatement {
  return env.DB.prepare(
    "INSERT INTO identities (provider, subject, user_id, email, created_at) VALUES (?, ?, ?, ?, ?)",
  ).bind(identity.provider, identity.subject, userId, identity.email ?? null, now);
}

async function emailAllowed(env: Env, email: string | undefined): Promise<boolean> {
  if (!email) return false;
  return (await env.DB.prepare("SELECT 1 FROM allowed_emails WHERE email = ?").bind(email).first()) !== null;
}

/** Nouveau compte : actif si son adresse est acceptée d'avance, en attente sinon. */
async function createAccount(env: Env, identity: VerifiedIdentity, displayName: string | undefined, now: number): Promise<User> {
  const status: AccessStatus = (await emailAllowed(env, identity.email)) ? "active" : "pending";
  const user: User = { id: crypto.randomUUID(), role: "member", display_name: displayName ?? null, status };
  // Le lot est une transaction : jamais de compte sans son identité.
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO users (id, role, display_name, status, status_changed_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(user.id, user.role, user.display_name, user.status, status === "active" ? now : null, now, now),
    identityInsert(env, identity, user.id, now),
  ]);
  return user;
}

export interface ResolvedAccount {
  user: User;
  /** Compte créé par cette connexion. */
  created: boolean;
}

/**
 * Compte de cette identité : déjà connu, rattaché par l'adresse e-mail
 * vérifiée, ou créé (en attente de validation).
 */
export async function resolveAccount(
  env: Env,
  identity: VerifiedIdentity,
  givenName: string | undefined,
  now: number,
): Promise<ResolvedAccount> {
  const displayName = (givenName ?? identity.givenName)?.slice(0, 60);

  let user = await userByIdentity(env, identity);
  if (!user && identity.email) {
    user = await userByEmail(env, identity.email);
    if (user) await identityInsert(env, identity, user.id, now).run();
  }
  if (!user) return { user: await createAccount(env, identity, displayName, now), created: true };

  // Apple ne donne le prénom qu'à la toute première connexion : on le garde.
  if (user.display_name === null && displayName) {
    await env.DB.prepare("UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?")
      .bind(displayName, now, user.id)
      .run();
    user = { ...user, display_name: displayName };
  }
  return { user, created: false };
}

/**
 * Premier déploiement : le code d'amorçage fait de ce compte l'administrateur,
 * tant qu'aucun administrateur n'existe. `forbidden` sinon, sans dire pourquoi.
 */
export async function claimAdmin(env: Env, userId: string, code: string, now: number): Promise<User> {
  if (!env.BOOTSTRAP_CODE || !(await safeEqual(normalizeCode(code), normalizeCode(env.BOOTSTRAP_CODE)))) {
    throw new ApiError("forbidden");
  }
  // La condition rend l'amorçage atomique : un seul administrateur, une fois.
  const result = await env.DB.prepare(
    `UPDATE users SET role = 'admin', status = 'active', status_changed_at = ?1, updated_at = ?1
     WHERE id = ?2 AND NOT EXISTS (SELECT 1 FROM users WHERE role = 'admin')`,
  )
    .bind(now, userId)
    .run();
  if (result.meta.changes !== 1) throw new ApiError("forbidden");
  const user = await findUser(env, userId);
  if (!user) throw new ApiError("unauthorized");
  return user;
}

/** Comptes administrateurs : prévenus d'une nouvelle demande d'accès. */
export async function adminIds(env: Env): Promise<string[]> {
  const rows = await env.DB.prepare("SELECT id FROM users WHERE role = 'admin'").all<{ id: string }>();
  return rows.results.map((row) => row.id);
}

/**
 * Marque le nonce comme utilisé. Faux s'il l'était déjà : le même jeton
 * d'identité présenté deux fois est refusé.
 */
export async function consumeNonce(env: Env, rawNonce: string, expiresAt: number, now: number): Promise<boolean> {
  await env.DB.prepare("DELETE FROM used_nonces WHERE expires_at < ?").bind(now).run();
  const result = await env.DB.prepare("INSERT OR IGNORE INTO used_nonces (nonce_hash, expires_at) VALUES (?, ?)")
    .bind(await sha256Hex(rawNonce), expiresAt)
    .run();
  return result.meta.changes === 1;
}

/** Supprime le compte ; les identités et sessions partent avec (ON DELETE CASCADE). */
export async function deleteAccount(env: Env, userId: string): Promise<void> {
  await env.DB.prepare("DELETE FROM users WHERE id = ?").bind(userId).run();
}

export async function hasAppleIdentity(env: Env, userId: string): Promise<boolean> {
  const row = await env.DB.prepare("SELECT 1 FROM identities WHERE user_id = ? AND provider = 'apple' LIMIT 1")
    .bind(userId)
    .first();
  return row !== null;
}
