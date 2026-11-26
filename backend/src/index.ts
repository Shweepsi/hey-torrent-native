/**
 * API de HeyWatchlist (Worker `heywatchlist-api`). Les apps n'y parlent que
 * par le cœur Rust. Routes sous `/v1` ; réponses JSON, erreurs en codes stables.
 * Rien n'est journalisé qui identifie une personne ou contient un jeton.
 */
import { consumeNonce, deleteAccount, findUser, hasAppleIdentity, publicUser, resolveAccount } from "./accounts";
import { revokeAppleAuthorization, type FetchLike } from "./apple";
import { invitationCode, normalizeCode, sha256Hex } from "./crypto";
import type { Env } from "./env";
import { ApiError, errorResponse, json, noContent, optionalString, readJson, requiredString } from "./http";
import { remoteKeySets, verifyIdToken, type KeySets, type Provider } from "./providers";
import { authenticate, openSession, refreshSession, revokeSession } from "./sessions";
import { TMDB_PREFIX, relayTmdb } from "./tmdb";

/** Ce qui varie entre la production et les tests. */
export interface Deps {
  keySets: KeySets;
  fetch: FetchLike;
  /** Heure courante, en secondes Unix. */
  now: () => number;
}

const INVITATION_TTL_DAYS = 14;

function provider(body: Record<string, unknown>): Provider {
  const value = body.provider;
  if (value !== "apple" && value !== "google") throw new ApiError("invalid_request");
  return value;
}

async function signIn(request: Request, env: Env, deps: Deps): Promise<Response> {
  const body = await readJson(request);
  const which = provider(body);
  const idToken = requiredString(body, "id_token", 8192);
  const nonce = requiredString(body, "nonce", 128);
  if (nonce.length < 16) throw new ApiError("invalid_request");
  const invitation = optionalString(body, "invitation_code", 64);
  const givenName = optionalString(body, "given_name", 60);
  const now = deps.now();

  const identity = await verifyIdToken(deps.keySets, env, which, idToken, nonce, now);
  // Le compte est résolu avant de consommer le nonce : si une invitation
  // manque, l'app peut renvoyer le même jeton avec le code saisi.
  const user = await resolveAccount(env, identity, invitation, givenName, now);
  if (!(await consumeNonce(env, nonce, identity.expiresAt, now))) throw new ApiError("invalid_token");

  const tokens = await openSession(env, user.id, user.role, now);
  return json({ ...tokens, user: await publicUser(env, user) });
}

async function refresh(request: Request, env: Env, deps: Deps): Promise<Response> {
  const body = await readJson(request);
  return json(await refreshSession(env, requiredString(body, "refresh_token", 256), deps.now()));
}

async function signOut(request: Request, env: Env, deps: Deps): Promise<Response> {
  const body = await readJson(request);
  await revokeSession(env, requiredString(body, "refresh_token", 256), deps.now());
  return noContent();
}

async function me(request: Request, env: Env, deps: Deps): Promise<Response> {
  const claims = await authenticate(request, env, deps.now());
  const user = await findUser(env, claims.userId);
  if (!user) throw new ApiError("unauthorized");
  return json({ user: await publicUser(env, user) });
}

async function deleteMe(request: Request, env: Env, deps: Deps): Promise<Response> {
  const now = deps.now();
  const claims = await authenticate(request, env, now);
  const body = await readJson(request);
  const appleCode = optionalString(body, "apple_authorization_code", 1024);
  if (appleCode && (await hasAppleIdentity(env, claims.userId))) {
    if (!(await revokeAppleAuthorization(env, deps.fetch, appleCode, now))) {
      console.warn("account deletion: apple revocation skipped or failed");
    }
  }
  await deleteAccount(env, claims.userId);
  return noContent();
}

/** Crée un code d'invitation (administrateur seulement), valable 14 jours. */
async function createInvitation(request: Request, env: Env, deps: Deps): Promise<Response> {
  const now = deps.now();
  const claims = await authenticate(request, env, now);
  const user = await findUser(env, claims.userId);
  if (!user) throw new ApiError("unauthorized");
  if (user.role !== "admin") throw new ApiError("forbidden");
  const code = invitationCode();
  const expiresAt = now + INVITATION_TTL_DAYS * 24 * 3600;
  await env.DB.prepare(
    "INSERT INTO invitations (id, code_hash, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
  )
    .bind(crypto.randomUUID(), await sha256Hex(normalizeCode(code)), user.id, now, expiresAt)
    .run();
  return json({ code, expires_at: expiresAt }, 201);
}

/** Réglages publics dont les apps ont besoin pour se connecter. */
function config(env: Env): Response {
  return json({
    google_web_client_id: env.GOOGLE_WEB_CLIENT_ID ?? null,
    google_ios_client_id: env.GOOGLE_IOS_CLIENT_ID ?? null,
  });
}

type Handler = (request: Request, env: Env, deps: Deps) => Promise<Response> | Response;

const ROUTES: Record<string, Partial<Record<string, Handler>>> = {
  "/v1/health": { GET: () => json({ status: "ok" }) },
  "/v1/config": { GET: (_request, env) => config(env) },
  "/v1/auth/sign-in": { POST: signIn },
  "/v1/auth/refresh": { POST: refresh },
  "/v1/auth/sign-out": { POST: signOut },
  "/v1/me": { GET: me, DELETE: deleteMe },
  "/v1/invitations": { POST: createInvitation },
};

/** Le relais TMDB répond à tout un préfixe (liste blanche dans `tmdb.ts`) ; les autres routes sont exactes. */
function findHandler(method: string, pathname: string): Handler | undefined {
  if (pathname.startsWith(TMDB_PREFIX)) {
    return method === "GET" ? (request, env, deps) => relayTmdb(request, env, deps.fetch, deps.now()) : undefined;
  }
  return ROUTES[pathname]?.[method];
}

export function createHandler(deps: Deps): ExportedHandler<Env> {
  return {
    async fetch(request, env) {
      const handler = findHandler(request.method, new URL(request.url).pathname);
      if (!handler) return errorResponse("not_found");
      try {
        return await handler(request, env, deps);
      } catch (error) {
        if (error instanceof ApiError) return errorResponse(error.code);
        // Seul le type de l'erreur est journalisé, jamais son contenu.
        console.error("unexpected error", error instanceof Error ? error.name : typeof error);
        return errorResponse("internal");
      }
    },
  };
}

export default createHandler({
  keySets: remoteKeySets(),
  fetch: (input, init) => fetch(input, init),
  now: () => Math.floor(Date.now() / 1000),
});
