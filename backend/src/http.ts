/**
 * Réponses de l'API. Les erreurs sont des codes stables (`{"error": "..."}`),
 * jamais des phrases : le cœur Rust les traduit en types, l'app en français.
 */
export type ErrorCode =
  | "invalid_request"
  | "invalid_token"
  | "access_pending"
  | "access_refused"
  | "unauthorized"
  | "forbidden"
  | "session_expired"
  | "not_found"
  | "conflict"
  | "provider_unavailable"
  | "assistant_not_configured"
  | "assistant_auth_failed"
  | "assistant_unavailable"
  | "plex_unreachable"
  | "plex_auth_failed"
  | "internal";

const STATUS: Record<ErrorCode, number> = {
  invalid_request: 400,
  invalid_token: 401,
  access_pending: 403,
  access_refused: 403,
  unauthorized: 401,
  forbidden: 403,
  session_expired: 401,
  not_found: 404,
  conflict: 409,
  provider_unavailable: 503,
  assistant_not_configured: 409,
  assistant_auth_failed: 502,
  assistant_unavailable: 503,
  plex_unreachable: 502,
  plex_auth_failed: 502,
  internal: 500,
};

/** Erreur prévue, transformée en réponse par le routeur. */
export class ApiError extends Error {
  constructor(readonly code: ErrorCode) {
    super(code);
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export function noContent(): Response {
  return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
}

export function errorResponse(code: ErrorCode): Response {
  return json({ error: code }, STATUS[code]);
}

/** Corps JSON d'une requête, limité à 16 Kio ; objet attendu. */
export async function readJson(request: Request): Promise<Record<string, unknown>> {
  const text = await request.text();
  if (text.length === 0) return {};
  if (text.length > 16_384) throw new ApiError("invalid_request");
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new ApiError("invalid_request");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ApiError("invalid_request");
  }
  return value as Record<string, unknown>;
}

/** Champ texte facultatif, coupé et borné ; `undefined` s'il est vide. */
export function optionalString(body: Record<string, unknown>, key: string, max: number): string | undefined {
  const value = body[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") throw new ApiError("invalid_request");
  const trimmed = value.trim();
  if (trimmed.length === 0) return undefined;
  if (trimmed.length > max) throw new ApiError("invalid_request");
  return trimmed;
}

export function requiredString(body: Record<string, unknown>, key: string, max: number): string {
  const value = optionalString(body, key, max);
  if (value === undefined) throw new ApiError("invalid_request");
  return value;
}
