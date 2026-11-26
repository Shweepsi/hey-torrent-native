/**
 * Faux Apple et faux Google : des clés RSA de test signent des jetons
 * d'identité au même format que les vrais. Les appels sortants du Worker
 * (révocation Apple, TMDB) passent par `deps.fetch`, simulé ici : ils sont
 * enregistrés dans `fetchCalls`. Aucun appel réseau.
 */
import { env } from "cloudflare:workers";
import { SignJWT, createLocalJWKSet, exportJWK, generateKeyPair, type CryptoKey } from "jose";
import { sha256Hex } from "../src/crypto";
import { createHandler, type Deps } from "../src/index";
import type { Platform, PushMessage, PushOutcome, PushSender } from "../src/push";
import type { Provider } from "../src/providers";

const KID = "test-key";

async function keyPair() {
  const { privateKey, publicKey } = await generateKeyPair("RS256");
  const jwk = { ...(await exportJWK(publicKey)), kid: KID, alg: "RS256", use: "sig" };
  return { privateKey, jwks: createLocalJWKSet({ keys: [jwk] }) };
}

const apple = await keyPair();
const google = await keyPair();
/** Clé que ni Apple ni Google ne publient : signature refusée. */
export const rogue = await keyPair();

export const NOW = 1_800_000_000;
let clock = NOW;
export function setNow(value: number) {
  clock = value;
}

/** Appels sortants du Worker : URL, corps, en-têtes (noms en minuscules) et délai armé. */
export const fetchCalls: {
  url: string;
  body: string;
  headers: Record<string, string>;
  signal: AbortSignal | undefined;
}[] = [];
type FetchReply = (url: string, init: RequestInit) => Response | Promise<Response>;
let fetchReply: FetchReply = () => new Response("{}", { status: 500 });
/** Une réponse qui lève une erreur ou rejette simule une panne réseau ou un délai dépassé. */
export function replyToFetch(reply: FetchReply) {
  fetchReply = reply;
}

/** Push « envoyées » par le faux expéditeur. */
export const pushes: { platform: Platform; token: string; message: PushMessage }[] = [];
let pushOutcome: (token: string) => PushOutcome = () => "sent";
export function replyToPush(reply: (token: string) => PushOutcome) {
  pushOutcome = reply;
}
const fakeSender: PushSender = {
  send: async (platform, token, message) => {
    pushes.push({ platform, token, message });
    return pushOutcome(token);
  },
};

export const deps: Deps = {
  keySets: { apple: apple.jwks, google: google.jwks },
  fetch: async (url, init) => {
    fetchCalls.push({
      url,
      body: String(init.body ?? ""),
      headers: Object.fromEntries(new Headers(init.headers).entries()),
      signal: init.signal ?? undefined,
    });
    return fetchReply(url, init);
  },
  now: () => clock,
  push: () => fakeSender,
};

const handler = createHandler(deps);

export async function call(
  method: string,
  path: string,
  body?: unknown,
  token?: string,
  workerEnv: Cloudflare.Env = env,
): Promise<Response> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  return send(
    new Request(`https://api.test${path}`, {
      method,
      headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }),
    workerEnv,
  );
}

/** Requête quelconque (formulaire du webhook Plex, par exemple). */
export async function send(request: Request, workerEnv: Cloudflare.Env = env): Promise<Response> {
  // `fetch` est toujours défini sur notre gestionnaire.
  return handler.fetch!(request as Request<unknown, IncomingRequestCfProperties>, workerEnv, {} as ExecutionContext);
}

export interface TokenOptions {
  subject?: string;
  email?: string;
  emailVerified?: boolean | string;
  givenName?: string;
  audience?: string;
  issuer?: string;
  nonce?: string;
  expiresIn?: number;
  key?: CryptoKey;
}

let nonceCounter = 0;
export function freshNonce(): string {
  nonceCounter += 1;
  return `nonce-de-test-${nonceCounter}-0123456789`;
}

/** Jeton d'identité signé, comme Apple ou Google le délivrent. */
export async function idToken(provider: Provider, rawNonce: string, options: TokenOptions = {}): Promise<string> {
  const claims: Record<string, unknown> = {
    // Apple : SHA-256 du nonce ; Google : nonce brut.
    nonce: options.nonce ?? (provider === "apple" ? await sha256Hex(rawNonce) : rawNonce),
  };
  if (options.email) {
    claims.email = options.email;
    claims.email_verified = options.emailVerified ?? (provider === "apple" ? "true" : true);
  }
  if (options.givenName) claims.given_name = options.givenName;
  const audience =
    options.audience ?? (provider === "apple" ? env.APPLE_CLIENT_ID : (env.GOOGLE_WEB_CLIENT_ID ?? ""));
  const issuer = options.issuer ?? (provider === "apple" ? "https://appleid.apple.com" : "https://accounts.google.com");
  return new SignJWT(claims)
    .setProtectedHeader({ alg: "RS256", kid: KID })
    .setIssuer(issuer)
    .setAudience(audience)
    .setSubject(options.subject ?? `${provider}-sujet-1`)
    .setIssuedAt(clock - 10)
    .setExpirationTime(clock + (options.expiresIn ?? 600))
    .sign(options.key ?? (provider === "apple" ? apple.privateKey : google.privateKey));
}

export interface SignInBody {
  provider: Provider;
  id_token: string;
  nonce: string;
  invitation_code?: string;
  given_name?: string;
}

/** Connexion complète ; renvoie la réponse brute. */
export async function signIn(provider: Provider, options: TokenOptions & { invitation?: string; givenNameField?: string } = {}) {
  const nonce = freshNonce();
  const body: SignInBody = { provider, id_token: await idToken(provider, nonce, options), nonce };
  if (options.invitation) body.invitation_code = options.invitation;
  if (options.givenNameField) body.given_name = options.givenNameField;
  return call("POST", "/v1/auth/sign-in", body);
}

export interface SessionBody {
  access_token: string;
  access_expires_in: number;
  refresh_token: string;
  user: { id: string; role: string; display_name: string | null; providers: string[] };
}

/** Crée l'administrateur avec le code d'amorçage. */
export async function bootstrapAdmin(): Promise<SessionBody> {
  const response = await signIn("apple", {
    subject: "admin-apple",
    invitation: env.BOOTSTRAP_CODE ?? "",
    givenNameField: "Loïc",
  });
  if (response.status !== 200) throw new Error(`amorçage refusé : ${response.status}`);
  return response.json();
}

/** Nouveau code d'invitation créé par cet administrateur. */
export async function invite(adminToken: string): Promise<string> {
  const response = await call("POST", "/v1/invitations", {}, adminToken);
  if (response.status !== 201) throw new Error(`invitation refusée : ${response.status}`);
  return ((await response.json()) as { code: string }).code;
}
