import { env } from "cloudflare:workers";
import { decodeJwt, decodeProtectedHeader, exportPKCS8, generateKeyPair } from "jose";
import { describe, expect, it } from "vitest";
import type { Env } from "../src/env";
import { LivePushSender, type PushMessage } from "../src/push";

// APNs, Google et FCM sont simulés : clés de test générées ici, aucun appel réseau.

const NOW = 1_800_000_000;
const MESSAGE: PushMessage = {
  title: "Matrix",
  bodyKey: "push_available_movie",
  bodyArgs: [],
  collapseKey: "movie-603",
  mediaType: "movie",
  tmdbId: 603,
};
const IOS_TOKEN = "ab".repeat(32);

const apnsKey = await exportPKCS8((await generateKeyPair("ES256", { extractable: true })).privateKey);
const googleKey = await exportPKCS8((await generateKeyPair("RS256", { extractable: true })).privateKey);

interface Call {
  url: string;
  headers: Record<string, string>;
  body: string;
}

function sender(overrides: Partial<Env>, reply: (url: string) => Response) {
  const calls: Call[] = [];
  const workerEnv = { ...env, ...overrides } as Env;
  const live = new LivePushSender(
    workerEnv,
    async (url, init) => {
      calls.push({ url, headers: Object.fromEntries(new Headers(init.headers).entries()), body: String(init.body ?? "") });
      return reply(url);
    },
    () => NOW,
  );
  return { live, calls };
}

const APNS: Partial<Env> = { APNS_KEY_ID: "KEY1234567", APPLE_TEAM_ID: "TEAM123456", APNS_PRIVATE_KEY: apnsKey };
const FCM: Partial<Env> = {
  FCM_SERVICE_ACCOUNT: JSON.stringify({
    project_id: "projet-de-test",
    client_email: "fcm@projet-de-test.iam.gserviceaccount.com",
    private_key: googleKey,
  }),
};

describe("APNs", () => {
  it("envoie une alerte traduite par l'app, signée ES256, et réutilise le jeton", async () => {
    const { live, calls } = sender(APNS, () => new Response(null, { status: 200 }));
    expect(await live.send("ios", IOS_TOKEN, MESSAGE)).toBe("sent");
    expect(await live.send("ios", IOS_TOKEN, MESSAGE)).toBe("sent");
    const [first, second] = calls;
    expect(first?.url).toBe(`https://api.push.apple.com/3/device/${IOS_TOKEN}`);
    expect(first?.headers["apns-topic"]).toBe(env.APPLE_CLIENT_ID);
    expect(first?.headers["apns-push-type"]).toBe("alert");
    const jwt = first?.headers.authorization?.replace("bearer ", "") ?? "";
    expect(decodeProtectedHeader(jwt)).toMatchObject({ alg: "ES256", kid: "KEY1234567" });
    expect(decodeJwt(jwt)).toMatchObject({ iss: "TEAM123456", iat: NOW });
    expect(second?.headers.authorization).toBe(first?.headers.authorization);
    expect(JSON.parse(first?.body ?? "{}")).toMatchObject({
      aps: { alert: { title: "Matrix", "loc-key": "push_available_movie", "loc-args": [] }, "thread-id": "movie-603" },
      media_type: "movie",
      tmdb_id: 603,
    });
  });

  it("signale un jeton périmé", async () => {
    const { live } = sender(APNS, () => Response.json({ reason: "Unregistered" }, { status: 410 }));
    expect(await live.send("ios", IOS_TOKEN, MESSAGE)).toBe("gone");
    const bad = sender(APNS, () => Response.json({ reason: "BadDeviceToken" }, { status: 400 }));
    expect(await bad.live.send("ios", IOS_TOKEN, MESSAGE)).toBe("gone");
  });

  it("échoue sans rien envoyer si la clé manque, et survit à une panne réseau", async () => {
    const { live, calls } = sender({ APNS_KEY_ID: "KEY1234567", APPLE_TEAM_ID: "TEAM123456" }, () => new Response(null));
    expect(await live.send("ios", IOS_TOKEN, MESSAGE)).toBe("failed");
    expect(calls).toHaveLength(0);
    const down = sender(APNS, () => {
      throw new TypeError("network");
    });
    expect(await down.live.send("ios", IOS_TOKEN, MESSAGE)).toBe("failed");
  });
});

describe("FCM", () => {
  function fcmReply(send: () => Response) {
    return (url: string) =>
      url.startsWith("https://oauth2.googleapis.com/")
        ? Response.json({ access_token: "jeton-google", expires_in: 3599 })
        : send();
  }

  it("obtient un jeton Google une fois et envoie un message Android traduit par l'app", async () => {
    const { live, calls } = sender(FCM, fcmReply(() => Response.json({ name: "projects/x/messages/1" })));
    expect(await live.send("android", "jeton-fcm", MESSAGE)).toBe("sent");
    expect(await live.send("android", "jeton-fcm", MESSAGE)).toBe("sent");
    expect(calls.map((c) => c.url)).toEqual([
      "https://oauth2.googleapis.com/token",
      "https://fcm.googleapis.com/v1/projects/projet-de-test/messages:send",
      "https://fcm.googleapis.com/v1/projects/projet-de-test/messages:send",
    ]);
    const assertion = new URLSearchParams(calls[0]?.body).get("assertion") ?? "";
    expect(decodeJwt(assertion)).toMatchObject({
      iss: "fcm@projet-de-test.iam.gserviceaccount.com",
      scope: "https://www.googleapis.com/auth/firebase.messaging",
    });
    expect(calls[1]?.headers.authorization).toBe("Bearer jeton-google");
    expect(JSON.parse(calls[1]?.body ?? "{}")).toEqual({
      message: {
        token: "jeton-fcm",
        data: { media_type: "movie", tmdb_id: "603" },
        android: {
          priority: "HIGH",
          collapse_key: "movie-603",
          notification: {
            title: "Matrix",
            body_loc_key: "push_available_movie",
            body_loc_args: [],
            channel_id: "available",
            tag: "movie-603",
          },
        },
      },
    });
  });

  it("signale un jeton désinscrit", async () => {
    const unregistered = Response.json(
      { error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } },
      { status: 404 },
    );
    const { live } = sender(FCM, fcmReply(() => unregistered));
    expect(await live.send("android", "jeton-fcm", MESSAGE)).toBe("gone");
  });

  it("échoue proprement si Google refuse ou si le compte de service est illisible", async () => {
    const refused = sender(FCM, () => new Response("{}", { status: 400 }));
    expect(await refused.live.send("android", "jeton-fcm", MESSAGE)).toBe("failed");
    const broken = sender({ FCM_SERVICE_ACCOUNT: "{pas du json" }, () => new Response(null));
    expect(await broken.live.send("android", "jeton-fcm", MESSAGE)).toBe("failed");
    expect(broken.calls).toHaveLength(0);
  });
});
