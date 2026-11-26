/**
 * Appareils qui reçoivent les push : `PUT /v1/devices` enregistre le jeton de
 * l'appareil pour la session en cours, `DELETE /v1/devices` l'oublie. Un jeton
 * déjà connu passe au compte qui l'enregistre (téléphone prêté, changement de
 * compte). Le jeton n'est jamais journalisé ni renvoyé.
 */
import type { Env } from "./env";
import { ApiError, noContent, readJson, requiredString } from "./http";
import type { Platform } from "./push";
import { authenticate } from "./sessions";

export const DEVICES_PATH = "/v1/devices";

/** Appareils gardés au plus par compte ; les plus anciens partent d'abord. */
const MAX_DEVICES_PER_USER = 10;

function platform(body: Record<string, unknown>): Platform {
  const value = body.platform;
  if (value !== "ios" && value !== "android") throw new ApiError("invalid_request");
  return value;
}

/** Jeton APNs (hexadécimal) ou FCM (base64url avec `:`), borné. */
function pushToken(body: Record<string, unknown>, which: Platform): string {
  const token = requiredString(body, "token", 4096);
  const shape = which === "ios" ? /^[0-9a-fA-F]{32,200}$/ : /^[A-Za-z0-9_:.-]{20,4096}$/;
  if (!shape.test(token)) throw new ApiError("invalid_request");
  return which === "ios" ? token.toLowerCase() : token;
}

export async function registerDevice(request: Request, env: Env, now: number): Promise<Response> {
  const claims = await authenticate(request, env, now);
  const body = await readJson(request);
  const which = platform(body);
  const token = pushToken(body, which);
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO devices (token, platform, user_id, session_id, created_at, updated_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?5)
       ON CONFLICT (token) DO UPDATE SET platform = ?2, user_id = ?3, session_id = ?4, updated_at = ?5`,
    ).bind(token, which, claims.userId, claims.sessionId, now),
    // Une seule entrée par session : le jeton d'un appareil change parfois.
    env.DB.prepare("DELETE FROM devices WHERE session_id = ? AND token <> ?").bind(claims.sessionId, token),
    env.DB.prepare(
      `DELETE FROM devices WHERE user_id = ?1 AND token NOT IN
         (SELECT token FROM devices WHERE user_id = ?1 ORDER BY updated_at DESC LIMIT ?2)`,
    ).bind(claims.userId, MAX_DEVICES_PER_USER),
  ]);
  return noContent();
}

export async function unregisterDevice(request: Request, env: Env, now: number): Promise<Response> {
  const claims = await authenticate(request, env, now);
  await env.DB.prepare("DELETE FROM devices WHERE session_id = ?").bind(claims.sessionId).run();
  return noContent();
}
