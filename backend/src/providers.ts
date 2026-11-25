/**
 * Vérification des jetons d'identité d'Apple et de Google (signature, émetteur,
 * audience, expiration), avec leurs clés publiques officielles.
 */
import { createRemoteJWKSet, errors, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from "jose";
import { sha256Hex } from "./crypto";
import type { Env } from "./env";
import { ApiError } from "./http";

export type Provider = "apple" | "google";

export interface VerifiedIdentity {
  provider: Provider;
  subject: string;
  /** Adresse vérifiée par le fournisseur, en minuscules. */
  email: string | undefined;
  /** Prénom présent dans le jeton (Google seulement). */
  givenName: string | undefined;
  /** Fin de validité du jeton, en secondes Unix. */
  expiresAt: number;
}

export interface KeySets {
  apple: JWTVerifyGetKey;
  google: JWTVerifyGetKey;
}

/** Clés publiques réelles ; jose les garde en cache dans l'isolat. */
export function remoteKeySets(): KeySets {
  return {
    apple: createRemoteJWKSet(new URL("https://appleid.apple.com/auth/keys")),
    google: createRemoteJWKSet(new URL("https://www.googleapis.com/oauth2/v3/certs")),
  };
}

const ISSUERS: Record<Provider, string[]> = {
  apple: ["https://appleid.apple.com"],
  google: ["https://accounts.google.com", "accounts.google.com"],
};

function audiences(provider: Provider, env: Env): string[] {
  if (provider === "apple") return [env.APPLE_CLIENT_ID];
  return [env.GOOGLE_WEB_CLIENT_ID, env.GOOGLE_IOS_CLIENT_ID].filter((id): id is string => Boolean(id));
}

/** Apple met le SHA-256 (hexadécimal) du nonce dans le jeton, Google le nonce brut. */
async function expectedNonce(provider: Provider, rawNonce: string): Promise<string> {
  return provider === "apple" ? sha256Hex(rawNonce) : rawNonce;
}

function isTrue(value: unknown): boolean {
  return value === true || value === "true";
}

export async function verifyIdToken(
  keySets: KeySets,
  env: Env,
  provider: Provider,
  idToken: string,
  rawNonce: string,
  now: number,
): Promise<VerifiedIdentity> {
  const audience = audiences(provider, env);
  if (audience.length === 0) throw new ApiError("provider_unavailable");

  let payload: JWTPayload;
  try {
    ({ payload } = await jwtVerify(idToken, keySets[provider], {
      issuer: ISSUERS[provider],
      audience,
      algorithms: ["RS256"],
      requiredClaims: ["sub", "exp", "iat"],
      currentDate: new Date(now * 1000),
      // Petite tolérance pour une horloge de téléphone en avance.
      clockTolerance: 30,
    }));
  } catch (error) {
    // Clés injoignables : le service d'Apple ou de Google, pas le jeton.
    if (error instanceof errors.JWKSTimeout || (error instanceof Error && error.name === "TypeError")) {
      throw new ApiError("provider_unavailable");
    }
    throw new ApiError("invalid_token");
  }

  if (typeof payload.nonce !== "string" || payload.nonce !== (await expectedNonce(provider, rawNonce))) {
    throw new ApiError("invalid_token");
  }
  if (typeof payload.sub !== "string" || payload.sub.length === 0 || typeof payload.exp !== "number") {
    throw new ApiError("invalid_token");
  }

  const email =
    typeof payload.email === "string" && isTrue(payload.email_verified) ? payload.email.toLowerCase() : undefined;
  const givenName =
    provider === "google" && typeof payload.given_name === "string" ? payload.given_name.trim() || undefined : undefined;

  return { provider, subject: payload.sub, email, givenName, expiresAt: payload.exp };
}
