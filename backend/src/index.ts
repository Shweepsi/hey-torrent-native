/**
 * API de HeyWatchlist (Worker `heywatchlist-api`). Les apps n'y parlent que
 * par le cœur Rust. Routes sous `/v1` ; réponses JSON, erreurs en codes stables.
 * Rien n'est journalisé qui identifie une personne ou contient un jeton.
 */
import { ADMIN_PREFIX, adminHandler } from "./admin";
import {
  adminIds,
  claimAdmin,
  consumeNonce,
  deleteAccount,
  findUser,
  hasAppleIdentity,
  publicUser,
  resolveAccount,
  type User,
} from "./accounts";
import { revokeAppleAuthorization, type FetchLike } from "./apple";
import type { Env } from "./env";
import { ApiError, errorResponse, json, noContent, optionalString, readJson, requiredString } from "./http";
import { remoteKeySets, verifyIdToken, type KeySets, type Provider } from "./providers";
import { devicesOfUser, devicesOfUsers, pushTo } from "./availability";
import { DEVICES_PATH, registerDevice, unregisterDevice } from "./devices";
import { LivePushSender, type PushSender } from "./push";
import { authenticate, authenticateSession, openSession, refreshSession, revokeSession } from "./sessions";
import { TMDB_PREFIX, relayTmdb } from "./tmdb";
import { HOOKS_PREFIX, WEBHOOK_SETTINGS_PATH, receiveHook, regenerateWebhook, webhookStatus } from "./webhooks";
import { WATCHLIST_PATH, deleteOrphanRequests, watchlistHandler } from "./watchlist";

/** Ce qui varie entre la production et les tests. */
export interface Deps {
  keySets: KeySets;
  fetch: FetchLike;
  /** Heure courante, en secondes Unix. */
  now: () => number;
  /** Expéditeur des push (APNs, FCM) pour cet environnement. */
  push: (env: Env) => PushSender;
}

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
  const givenName = optionalString(body, "given_name", 60);
  const now = deps.now();

  const identity = await verifyIdToken(deps.keySets, env, which, idToken, nonce, now);
  if (!(await consumeNonce(env, nonce, identity.expiresAt, now))) throw new ApiError("invalid_token");
  const { user, created } = await resolveAccount(env, identity, givenName, now);
  if (created && user.status === "pending") await announceAccessRequest(env, deps, user, now);

  const tokens = await openSession(env, user.id, user.role, now);
  return json({ ...tokens, user: await publicUser(env, user) });
}

/** Prévient l'administrateur qu'un nouveau compte attend sa validation. Jamais bloquant. */
async function announceAccessRequest(env: Env, deps: Deps, user: User, now: number): Promise<void> {
  try {
    const devices = await devicesOfUsers(env, await adminIds(env), now);
    await pushTo(env, deps.push(env), devices, {
      title: "HeyWatchlist",
      bodyKey: user.display_name ? "push_access_request_named" : "push_access_request",
      bodyArgs: user.display_name ? [user.display_name] : [],
      collapseKey: "access-requests",
      mediaType: "movie",
      tmdbId: 0,
    });
  } catch (error) {
    console.warn("access request push failed", error instanceof Error ? error.name : typeof error);
  }
}

/** `POST /v1/auth/bootstrap` `{code}` : premier administrateur, avec le code d'amorçage. */
async function bootstrap(request: Request, env: Env, deps: Deps): Promise<Response> {
  const now = deps.now();
  const claims = await authenticateSession(request, env, now);
  const code = requiredString(await readJson(request), "code", 64);
  const user = await claimAdmin(env, claims.userId, code, now);
  return json({ user: await publicUser(env, user) });
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

/** Profil, avec l'état de l'accès : permis à un compte en attente ou refusé. */
async function me(request: Request, env: Env, deps: Deps): Promise<Response> {
  const claims = await authenticateSession(request, env, deps.now());
  const user = await findUser(env, claims.userId);
  if (!user) throw new ApiError("unauthorized");
  return json({ user: await publicUser(env, user) });
}

async function deleteMe(request: Request, env: Env, deps: Deps): Promise<Response> {
  const now = deps.now();
  // Permis quel que soit l'état : on peut toujours retirer sa demande d'accès.
  const claims = await authenticateSession(request, env, now);
  const body = await readJson(request);
  const appleCode = optionalString(body, "apple_authorization_code", 1024);
  if (appleCode && (await hasAppleIdentity(env, claims.userId))) {
    if (!(await revokeAppleAuthorization(env, deps.fetch, appleCode, now))) {
      console.warn("account deletion: apple revocation skipped or failed");
    }
  }
  await deleteAccount(env, claims.userId);
  // Ses entrées de watchlist sont parties avec le compte : ses demandes que
  // plus personne n'attend aussi.
  await deleteOrphanRequests(env);
  return noContent();
}

/** Push d'essai vers les appareils de la personne connectée ; renvoie le nombre envoyé. */
async function testPush(request: Request, env: Env, deps: Deps): Promise<Response> {
  const now = deps.now();
  const claims = await authenticate(request, env, now);
  const devices = await devicesOfUser(env, claims.userId, now);
  const sent = await pushTo(env, deps.push(env), devices, {
    title: "HeyWatchlist",
    bodyKey: "push_test",
    bodyArgs: [],
    collapseKey: "test",
    mediaType: "movie",
    tmdbId: 0,
  });
  return json({ devices: devices.length, sent });
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
  "/v1/auth/bootstrap": { POST: bootstrap },
  "/v1/me": { GET: me, DELETE: deleteMe },
  [DEVICES_PATH]: {
    PUT: (request, env, deps) => registerDevice(request, env, deps.now()),
    DELETE: (request, env, deps) => unregisterDevice(request, env, deps.now()),
  },
  "/v1/notifications/test": { POST: testPush },
  [WEBHOOK_SETTINGS_PATH]: {
    GET: (request, env, deps) => webhookStatus(request, env, deps.now()),
    POST: (request, env, deps) => regenerateWebhook(request, env, deps.now()),
  },
};

/**
 * Le relais TMDB répond à tout un préfixe (liste blanche dans `tmdb.ts`), la
 * watchlist et l'administration à leurs chemins (`watchlist.ts`, `admin.ts`) ;
 * les autres routes sont exactes.
 */
function findHandler(method: string, pathname: string): Handler | undefined {
  if (pathname.startsWith(TMDB_PREFIX)) {
    return method === "GET" ? (request, env, deps) => relayTmdb(request, env, deps.fetch, deps.now()) : undefined;
  }
  if (pathname.startsWith(HOOKS_PREFIX)) {
    return method === "POST"
      ? (request, env, deps) => receiveHook(request, env, deps.fetch, deps.push(env), deps.now())
      : undefined;
  }
  if (pathname.startsWith(ADMIN_PREFIX)) {
    const handler = adminHandler(method, pathname);
    return handler ? (request, env, deps) => handler(request, env, deps.now(), deps.push(env)) : undefined;
  }
  if (pathname === WATCHLIST_PATH || pathname.startsWith(`${WATCHLIST_PATH}/`)) {
    const handler = watchlistHandler(method, pathname);
    return handler ? (request, env, deps) => handler(request, env, deps.fetch, deps.now()) : undefined;
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

const fetchLive: FetchLike = (input, init) => fetch(input, init);
const nowLive = () => Math.floor(Date.now() / 1000);
// Un expéditeur par environnement : ses jetons APNs et Google restent en
// mémoire le temps de leur validité.
const senders = new WeakMap<Env, PushSender>();

export default createHandler({
  keySets: remoteKeySets(),
  fetch: fetchLive,
  now: nowLive,
  push: (env) => {
    let sender = senders.get(env);
    if (!sender) {
      sender = new LivePushSender(env, fetchLive, nowLive);
      senders.set(env, sender);
    }
    return sender;
  },
});
