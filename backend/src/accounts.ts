/**
 * Comptes sur invitation : retrouver, rattacher ou créer le compte d'une
 * identité Apple ou Google vérifiée.
 */
import { normalizeCode, safeEqual, sha256Hex } from "./crypto";
import type { Env } from "./env";
import { ApiError } from "./http";
import type { VerifiedIdentity } from "./providers";
import type { Role } from "./sessions";

export interface User {
  id: string;
  role: Role;
  display_name: string | null;
}

export interface PublicUser extends User {
  /** Fournisseurs rattachés : l'app iPhone sait ainsi s'il faut révoquer Apple. */
  providers: string[];
}

/** Ce que l'API renvoie d'un compte. */
export async function publicUser(env: Env, user: User): Promise<PublicUser> {
  const rows = await env.DB.prepare("SELECT provider FROM identities WHERE user_id = ? ORDER BY provider")
    .bind(user.id)
    .all<{ provider: string }>();
  return {
    id: user.id,
    role: user.role,
    display_name: user.display_name,
    providers: rows.results.map((row) => row.provider),
  };
}

export async function findUser(env: Env, userId: string): Promise<User | null> {
  return env.DB.prepare("SELECT id, role, display_name FROM users WHERE id = ?").bind(userId).first<User>();
}

async function userByIdentity(env: Env, identity: VerifiedIdentity): Promise<User | null> {
  return env.DB.prepare(
    `SELECT u.id, u.role, u.display_name FROM identities i JOIN users u ON u.id = i.user_id
     WHERE i.provider = ? AND i.subject = ?`,
  )
    .bind(identity.provider, identity.subject)
    .first<User>();
}

/** Compte qui a déjà une identité vérifiée avec la même adresse e-mail. */
async function userByEmail(env: Env, email: string): Promise<User | null> {
  return env.DB.prepare(
    `SELECT u.id, u.role, u.display_name FROM identities i JOIN users u ON u.id = i.user_id
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

function userInsert(env: Env, user: User, now: number): D1PreparedStatement {
  return env.DB.prepare(
    "INSERT INTO users (id, role, display_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
  ).bind(user.id, user.role, user.display_name, now, now);
}

async function adminExists(env: Env): Promise<boolean> {
  return (await env.DB.prepare("SELECT 1 FROM users WHERE role = 'admin' LIMIT 1").first()) !== null;
}

/** Crée le compte avec l'invitation donnée, ou lève l'erreur adaptée. */
async function createWithInvitation(
  env: Env,
  identity: VerifiedIdentity,
  code: string | undefined,
  displayName: string | undefined,
  now: number,
): Promise<User> {
  if (code === undefined) throw new ApiError("invitation_required");
  const normalized = normalizeCode(code);
  const user: User = { id: crypto.randomUUID(), role: "member", display_name: displayName ?? null };

  // Premier déploiement : le code d'amorçage crée l'administrateur, une fois.
  if (env.BOOTSTRAP_CODE && !(await adminExists(env)) && (await safeEqual(normalized, normalizeCode(env.BOOTSTRAP_CODE)))) {
    user.role = "admin";
    await env.DB.batch([userInsert(env, user, now), identityInsert(env, identity, user.id, now)]);
    return user;
  }

  const invitation = await env.DB.prepare(
    `SELECT id FROM invitations
     WHERE code_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?`,
  )
    .bind(await sha256Hex(normalized), now)
    .first<{ id: string }>();
  if (!invitation) throw new ApiError("invitation_invalid");

  // Le lot est une transaction : l'invitation n'est consommée qu'une fois, et
  // jamais sans le compte qui va avec.
  const results = await env.DB.batch([
    userInsert(env, user, now),
    env.DB.prepare("UPDATE invitations SET used_at = ?, used_by = ? WHERE id = ? AND used_at IS NULL").bind(
      now,
      user.id,
      invitation.id,
    ),
    identityInsert(env, identity, user.id, now),
  ]);
  if (results[1]?.meta.changes !== 1) {
    // Invitation prise entre-temps par quelqu'un d'autre : on défait le compte.
    await env.DB.prepare("DELETE FROM users WHERE id = ?").bind(user.id).run();
    throw new ApiError("invitation_invalid");
  }
  return user;
}

/**
 * Compte de cette identité : déjà connu, rattaché par l'adresse e-mail
 * vérifiée, ou créé avec une invitation.
 */
export async function resolveAccount(
  env: Env,
  identity: VerifiedIdentity,
  invitationCode: string | undefined,
  givenName: string | undefined,
  now: number,
): Promise<User> {
  const displayName = (givenName ?? identity.givenName)?.slice(0, 60);

  let user = await userByIdentity(env, identity);
  if (!user && identity.email) {
    user = await userByEmail(env, identity.email);
    if (user) await identityInsert(env, identity, user.id, now).run();
  }
  if (!user) return createWithInvitation(env, identity, invitationCode, displayName, now);

  // Apple ne donne le prénom qu'à la toute première connexion : on le garde.
  if (user.display_name === null && displayName) {
    await env.DB.prepare("UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?")
      .bind(displayName, now, user.id)
      .run();
    user = { ...user, display_name: displayName };
  }
  return user;
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
