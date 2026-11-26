/**
 * Révocation de « Se connecter avec Apple » à la suppression du compte
 * (exigence Apple 5.1.1(v)). L'app envoie un code d'autorisation tout juste
 * obtenu ; le Worker l'échange contre un jeton puis révoque ce jeton.
 * Rien n'est gardé : aucun jeton Apple n'est stocké dans D1.
 */
import { SignJWT, importPKCS8 } from "jose";
import type { Env } from "./env";

const APPLE = "https://appleid.apple.com";

export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export function appleRevocationConfigured(env: Env): boolean {
  return Boolean(env.APPLE_TEAM_ID && env.APPLE_SIGNIN_KEY_ID && env.APPLE_SIGNIN_PRIVATE_KEY);
}

/** Secret client d'Apple : JWT ES256 signé avec la clé « Sign in with Apple ». */
async function clientSecret(env: Env, now: number): Promise<string> {
  const pem = (env.APPLE_SIGNIN_PRIVATE_KEY ?? "").replaceAll("\\n", "\n");
  const key = await importPKCS8(pem, "ES256");
  return new SignJWT({})
    .setProtectedHeader({ alg: "ES256", kid: env.APPLE_SIGNIN_KEY_ID ?? "" })
    .setIssuer(env.APPLE_TEAM_ID ?? "")
    .setSubject(env.APPLE_CLIENT_ID)
    .setAudience(APPLE)
    .setIssuedAt(now)
    .setExpirationTime(now + 300)
    .sign(key);
}

function form(values: Record<string, string>): RequestInit {
  return {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(values).toString(),
    signal: AbortSignal.timeout(10_000),
  };
}

/**
 * Échange le code puis révoque le jeton obtenu. Renvoie `true` si Apple a
 * confirmé la révocation. Ne lève jamais : la suppression du compte continue.
 */
export async function revokeAppleAuthorization(
  env: Env,
  fetcher: FetchLike,
  authorizationCode: string,
  now: number,
): Promise<boolean> {
  if (!appleRevocationConfigured(env)) return false;
  try {
    const secret = await clientSecret(env, now);
    const exchange = await fetcher(
      `${APPLE}/auth/token`,
      form({
        client_id: env.APPLE_CLIENT_ID,
        client_secret: secret,
        code: authorizationCode,
        grant_type: "authorization_code",
      }),
    );
    if (!exchange.ok) return false;
    const tokens = (await exchange.json()) as { refresh_token?: unknown; access_token?: unknown };
    const token = typeof tokens.refresh_token === "string" ? tokens.refresh_token : tokens.access_token;
    if (typeof token !== "string") return false;
    const revoke = await fetcher(
      `${APPLE}/auth/revoke`,
      form({
        client_id: env.APPLE_CLIENT_ID,
        client_secret: secret,
        token,
        token_type_hint: typeof tokens.refresh_token === "string" ? "refresh_token" : "access_token",
      }),
    );
    return revoke.ok;
  } catch {
    return false;
  }
}
