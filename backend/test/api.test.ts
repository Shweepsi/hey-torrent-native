import { env } from "cloudflare:workers";
import { exportPKCS8, generateKeyPair } from "jose";
import { beforeEach, describe, expect, it } from "vitest";
import { verifyIdToken } from "../src/providers";
import { REFRESH_GRACE_SECONDS } from "../src/sessions";
import {
  NOW,
  bootstrapAdmin,
  call,
  deps,
  fetchCalls,
  freshNonce,
  idToken,
  replyToFetch,
  rogue,
  setNow,
  pushes,
  signIn,
  signInMember,
  type SessionBody,
} from "./fakes";

async function error(response: Response): Promise<string> {
  return ((await response.json()) as { error: string }).error;
}

async function userCount(): Promise<number> {
  const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first<{ n: number }>();
  return row?.n ?? -1;
}

// La base est partagée par les tests de ce fichier : l'administrateur est créé
// une fois, chaque test utilise ses propres identités.
let admin: SessionBody;
let subjectCounter = 0;
function subject(): string {
  subjectCounter += 1;
  return `sujet-${subjectCounter}`;
}

beforeEach(async () => {
  setNow(NOW);
  fetchCalls.length = 0;
  if (!admin) admin = await bootstrapAdmin();
});

describe("routes publiques", () => {
  it("répond à /v1/health", async () => {
    const response = await call("GET", "/v1/health");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "ok" });
  });

  it("donne les identifiants publics de Google", async () => {
    expect(await (await call("GET", "/v1/config")).json()).toEqual({
      google_web_client_id: env.GOOGLE_WEB_CLIENT_ID,
      google_ios_client_id: env.GOOGLE_IOS_CLIENT_ID,
    });
  });

  it("refuse une route ou une méthode inconnue", async () => {
    expect(await error(await call("GET", "/v1/nope"))).toBe("not_found");
    expect(await error(await call("PUT", "/v1/me"))).toBe("not_found");
  });

  it("refuse un corps illisible", async () => {
    expect(await error(await call("POST", "/v1/auth/sign-in", "pas un objet"))).toBe("invalid_request");
    expect(await error(await call("POST", "/v1/auth/sign-in", { provider: "facebook" }))).toBe("invalid_request");
  });
});

describe("amorçage de l'administrateur", () => {
  it("crée l'administrateur avec son prénom", () => {
    expect(admin.user.role).toBe("admin");
    expect(admin.user.status).toBe("active");
    expect(admin.user.display_name).toBe("Loïc");
    expect(admin.user.providers).toEqual(["apple"]);
    expect(admin.access_expires_in).toBe(900);
  });

  it("n'accepte plus le code d'amorçage ensuite", async () => {
    const session = (await (await signIn("apple", { subject: subject() })).json()) as SessionBody;
    const response = await call("POST", "/v1/auth/bootstrap", { code: env.BOOTSTRAP_CODE ?? "" }, session.access_token);
    expect(await error(response)).toBe("forbidden");
  });

  it("refuse un code faux, et sans session", async () => {
    const session = (await (await signIn("apple", { subject: subject() })).json()) as SessionBody;
    expect(await error(await call("POST", "/v1/auth/bootstrap", { code: "AAAA-BBBB-CCCC" }, session.access_token))).toBe(
      "forbidden",
    );
    expect(await error(await call("POST", "/v1/auth/bootstrap", { code: env.BOOTSTRAP_CODE ?? "" }))).toBe("unauthorized");
  });

  it("reconnaît l'administrateur à sa connexion suivante", async () => {
    const response = await signIn("apple", { subject: "admin-apple" });
    const body = (await response.json()) as SessionBody;
    expect(body.user).toEqual(admin.user);
  });
});

describe("accès sur validation", () => {
  it("crée un compte en attente pour une identité inconnue", async () => {
    const before = await userCount();
    const response = await signIn("google", { subject: subject(), givenName: "Camille" });
    expect(response.status).toBe(200);
    const body = (await response.json()) as SessionBody;
    expect(body.user).toMatchObject({ role: "member", status: "pending", display_name: "Camille" });
    expect(await userCount()).toBe(before + 1);
  });

  it("prévient l'administrateur d'une nouvelle demande, avec le prénom", async () => {
    await call("PUT", "/v1/devices", { platform: "ios", token: "b2".repeat(32) }, admin.access_token);
    pushes.length = 0;
    await signIn("google", { subject: subject(), givenName: "Inès" });
    expect(pushes.map((p) => [p.message.bodyKey, p.message.bodyArgs])).toEqual([["push_access_request_named", ["Inès"]]]);
    pushes.length = 0;
    await signIn("apple", { subject: subject() });
    expect(pushes.map((p) => p.message.bodyKey)).toEqual(["push_access_request"]);
    // Une connexion suivante du même compte ne prévient pas de nouveau.
    pushes.length = 0;
    await signIn("apple", { subject: "admin-apple" });
    expect(pushes).toHaveLength(0);
  });

  it("ne donne rien à un compte en attente, sauf son profil", async () => {
    const session = (await (await signIn("google", { subject: subject() })).json()) as SessionBody;
    expect(await error(await call("GET", "/v1/watchlist", undefined, session.access_token))).toBe("access_pending");
    expect(await error(await call("GET", "/v1/tmdb/trending/all/week", undefined, session.access_token))).toBe(
      "access_pending",
    );
    const me = (await (await call("GET", "/v1/me", undefined, session.access_token)).json()) as SessionBody;
    expect(me.user.status).toBe("pending");
    // L'appareil s'enregistre quand même : il sera prévenu de l'acceptation.
    expect(
      (await call("PUT", "/v1/devices", { platform: "android", token: "fcm-pending_ABCDEFGHIJKLMNOPQRST" }, session.access_token))
        .status,
    ).toBe(204);
  });

  it("ignore un ancien code d'invitation envoyé par une ancienne app", async () => {
    const nonce = freshNonce();
    const token = await idToken("google", nonce, { subject: subject() });
    const response = await call("POST", "/v1/auth/sign-in", { provider: "google", id_token: token, nonce, invitation_code: "X" });
    expect(((await response.json()) as SessionBody).user.status).toBe("pending");
  });
});

describe("vérification des jetons d'identité", () => {
  async function rejected(options: Parameters<typeof idToken>[2], provider: "apple" | "google" = "apple") {
    const nonce = freshNonce();
    const token = await idToken(provider, nonce, { subject: "admin-apple", ...options });
    return error(await call("POST", "/v1/auth/sign-in", { provider, id_token: token, nonce }));
  }

  it("refuse une signature inconnue", async () => {
    expect(await rejected({ key: rogue.privateKey })).toBe("invalid_token");
  });

  it("refuse un jeton destiné à une autre app", async () => {
    expect(await rejected({ audience: "com.example.autre" })).toBe("invalid_token");
    expect(await rejected({ audience: "autre.apps.googleusercontent.com" }, "google")).toBe("invalid_token");
  });

  it("refuse un autre émetteur", async () => {
    expect(await rejected({ issuer: "https://example.com" })).toBe("invalid_token");
  });

  it("refuse un jeton expiré", async () => {
    expect(await rejected({ expiresIn: -60 })).toBe("invalid_token");
  });

  it("refuse un nonce différent", async () => {
    expect(await rejected({ nonce: "autre-nonce-0123456789" })).toBe("invalid_token");
  });

  it("refuse un jeton déjà utilisé", async () => {
    const nonce = freshNonce();
    const token = await idToken("apple", nonce, { subject: "admin-apple" });
    const body = { provider: "apple", id_token: token, nonce };
    expect((await call("POST", "/v1/auth/sign-in", body)).status).toBe(200);
    expect(await error(await call("POST", "/v1/auth/sign-in", body))).toBe("invalid_token");
  });

  it("accepte le client iOS de Google", async () => {
    const nonce = freshNonce();
    const token = await idToken("google", nonce, { subject: subject(), audience: env.GOOGLE_IOS_CLIENT_ID ?? "" });
    expect((await call("POST", "/v1/auth/sign-in", { provider: "google", id_token: token, nonce })).status).toBe(200);
  });

  it("signale Google non configuré", async () => {
    const { GOOGLE_WEB_CLIENT_ID: _web, GOOGLE_IOS_CLIENT_ID: _ios, ...unconfigured } = env;
    const nonce = freshNonce();
    await expect(
      verifyIdToken(deps.keySets, unconfigured, "google", await idToken("google", nonce), nonce, NOW),
    ).rejects.toMatchObject({ code: "provider_unavailable" });
  });
});

describe("même personne, deux fournisseurs", () => {
  it("rattache Google au compte Apple de même adresse vérifiée", async () => {
    const email = `${subject()}@example.com`;
    const apple = await signInMember("apple", { subject: subject(), email, givenNameField: "Sam" });
    const google = (await (await signIn("google", { subject: subject(), email: email.toUpperCase() })).json()) as SessionBody;
    expect(google.user).toEqual({ ...apple.user, providers: ["apple", "google"] });
  });

  it("ne rattache pas une adresse non vérifiée", async () => {
    const email = `${subject()}@example.com`;
    const apple = await signInMember("apple", { subject: subject(), email });
    const response = await signIn("google", { subject: subject(), email, emailVerified: false });
    const google = (await response.json()) as SessionBody;
    expect(google.user.id).not.toBe(apple.user.id);
    expect(google.user.status).toBe("pending");
  });
});

describe("prénom", () => {
  it("garde le prénom donné par Apple à la première connexion", async () => {
    const sub = subject();
    await signIn("apple", { subject: sub, givenNameField: "Alex" });
    // Les connexions suivantes n'ont plus de prénom ; un autre ne le remplace pas.
    const again = (await (await signIn("apple", { subject: sub })).json()) as SessionBody;
    expect(again.user.display_name).toBe("Alex");
    const other = (await (await signIn("apple", { subject: sub, givenNameField: "Autre" })).json()) as SessionBody;
    expect(other.user.display_name).toBe("Alex");
  });

  it("complète un compte sans prénom", async () => {
    const sub = subject();
    const first = (await (await signIn("apple", { subject: sub })).json()) as SessionBody;
    expect(first.user.display_name).toBeNull();
    const second = (await (await signIn("apple", { subject: sub, givenNameField: "Noa" })).json()) as SessionBody;
    expect(second.user.display_name).toBe("Noa");
  });
});

describe("sessions", () => {
  async function member(): Promise<SessionBody> {
    return signInMember("google", { subject: subject(), givenName: "Lou" });
  }

  it("donne le compte avec le jeton d'accès", async () => {
    const session = await member();
    const response = await call("GET", "/v1/me", undefined, session.access_token);
    expect(await response.json()).toEqual({ user: session.user });
  });

  it("refuse un jeton d'accès absent, faux ou expiré", async () => {
    const session = await member();
    expect(await error(await call("GET", "/v1/me"))).toBe("unauthorized");
    expect(await error(await call("GET", "/v1/me", undefined, "abc.def.ghi"))).toBe("unauthorized");
    setNow(NOW + 901);
    expect(await error(await call("GET", "/v1/me", undefined, session.access_token))).toBe("unauthorized");
  });

  it("renouvelle les deux jetons", async () => {
    const session = await member();
    setNow(NOW + 3600);
    const response = await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token });
    const next = (await response.json()) as SessionBody;
    expect(next.refresh_token).not.toBe(session.refresh_token);
    expect((await call("GET", "/v1/me", undefined, next.access_token)).status).toBe(200);
  });

  it("accepte encore le jeton précédent si la réponse s'est perdue", async () => {
    const session = await member();
    // Réponse perdue : l'app n'a jamais reçu `lost` et renvoie l'ancien jeton.
    const lost = (await (await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token })).json()) as SessionBody;
    setNow(NOW + 3 * 3600);
    const retry = await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token });
    expect(retry.status).toBe(200);
    const next = (await retry.json()) as SessionBody;
    expect((await call("GET", "/v1/me", undefined, next.access_token)).status).toBe(200);
    // Le jeton jamais reçu est remplacé ; le nouveau continue de servir.
    expect(await error(await call("POST", "/v1/auth/refresh", { refresh_token: lost.refresh_token }))).toBe(
      "session_expired",
    );
    expect((await call("POST", "/v1/auth/refresh", { refresh_token: next.refresh_token })).status).toBe(200);
  });

  it("coupe la session si un ancien jeton revient après le délai de grâce", async () => {
    const session = await member();
    const next = (await (await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token })).json()) as SessionBody;
    setNow(NOW + REFRESH_GRACE_SECONDS + 1);
    expect(await error(await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token }))).toBe(
      "session_expired",
    );
    expect(await error(await call("POST", "/v1/auth/refresh", { refresh_token: next.refresh_token }))).toBe(
      "session_expired",
    );
  });

  it("n'accepte que le jeton immédiatement précédent", async () => {
    const session = await member();
    const second = (await (await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token })).json()) as SessionBody;
    const third = (await (await call("POST", "/v1/auth/refresh", { refresh_token: second.refresh_token })).json()) as SessionBody;
    expect(await error(await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token }))).toBe(
      "session_expired",
    );
    expect((await call("POST", "/v1/auth/refresh", { refresh_token: third.refresh_token })).status).toBe(200);
  });

  it("expire après 90 jours sans usage", async () => {
    const session = await member();
    setNow(NOW + 90 * 24 * 3600 + 1);
    expect(await error(await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token }))).toBe(
      "session_expired",
    );
  });

  it("se ferme à la déconnexion", async () => {
    const session = await member();
    expect((await call("POST", "/v1/auth/sign-out", { refresh_token: session.refresh_token })).status).toBe(204);
    expect(await error(await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token }))).toBe(
      "session_expired",
    );
  });
});

describe("suppression du compte", () => {
  it("est permise à un compte en attente (retirer sa demande)", async () => {
    const session = (await (await signIn("google", { subject: subject() })).json()) as SessionBody;
    expect((await call("DELETE", "/v1/me", {}, session.access_token)).status).toBe(204);
    expect(await env.DB.prepare("SELECT 1 FROM users WHERE id = ?").bind(session.user.id).first()).toBeNull();
  });

  it("efface le compte, ses identités et ses sessions", async () => {
    const sub = subject();
    const session = await signInMember("google", { subject: sub });
    expect((await call("DELETE", "/v1/me", {}, session.access_token)).status).toBe(204);

    expect(await env.DB.prepare("SELECT 1 FROM users WHERE id = ?").bind(session.user.id).first()).toBeNull();
    expect(await env.DB.prepare("SELECT 1 FROM identities WHERE user_id = ?").bind(session.user.id).first()).toBeNull();
    expect(await env.DB.prepare("SELECT 1 FROM sessions WHERE user_id = ?").bind(session.user.id).first()).toBeNull();
    expect(await error(await call("GET", "/v1/me", undefined, session.access_token))).toBe("unauthorized");
    expect(await error(await call("POST", "/v1/auth/refresh", { refresh_token: session.refresh_token }))).toBe(
      "session_expired",
    );
    // Revenir crée un nouveau compte, de nouveau en attente.
    const back = (await (await signIn("google", { subject: sub })).json()) as SessionBody;
    expect(back.user.status).toBe("pending");
    expect(fetchCalls).toHaveLength(0);
  });

  it("révoque la connexion Apple quand la clé est configurée", async () => {
    const { privateKey } = await generateKeyPair("ES256", { extractable: true });
    const appleEnv = {
      ...env,
      APPLE_TEAM_ID: "TEAMID1234",
      APPLE_SIGNIN_KEY_ID: "KEYID12345",
      APPLE_SIGNIN_PRIVATE_KEY: await exportPKCS8(privateKey),
    };
    replyToFetch((url) =>
      url.endsWith("/auth/token")
        ? Response.json({ refresh_token: "apple-refresh", access_token: "apple-access" })
        : new Response(null, { status: 200 }),
    );
    const session = await signInMember("apple", { subject: subject() });
    const response = await call("DELETE", "/v1/me", { apple_authorization_code: "code-apple" }, session.access_token, appleEnv);
    expect(response.status).toBe(204);
    expect(fetchCalls.map((c) => new URL(c.url).pathname)).toEqual(["/auth/token", "/auth/revoke"]);
    const exchange = new URLSearchParams(fetchCalls[0]?.body);
    expect(exchange.get("code")).toBe("code-apple");
    expect(exchange.get("client_id")).toBe(env.APPLE_CLIENT_ID);
    expect(exchange.get("client_secret")?.split(".")).toHaveLength(3);
    const revoke = new URLSearchParams(fetchCalls[1]?.body);
    expect(revoke.get("token")).toBe("apple-refresh");
    expect(revoke.get("token_type_hint")).toBe("refresh_token");
  });

  it("supprime quand même le compte si Apple ne répond pas", async () => {
    const { privateKey } = await generateKeyPair("ES256", { extractable: true });
    const appleEnv = {
      ...env,
      APPLE_TEAM_ID: "TEAMID1234",
      APPLE_SIGNIN_KEY_ID: "KEYID12345",
      APPLE_SIGNIN_PRIVATE_KEY: await exportPKCS8(privateKey),
    };
    replyToFetch(() => new Response("{}", { status: 503 }));
    const session = await signInMember("apple", { subject: subject() });
    expect((await call("DELETE", "/v1/me", { apple_authorization_code: "x" }, session.access_token, appleEnv)).status).toBe(
      204,
    );
    expect(await env.DB.prepare("SELECT 1 FROM users WHERE id = ?").bind(session.user.id).first()).toBeNull();
  });
});
