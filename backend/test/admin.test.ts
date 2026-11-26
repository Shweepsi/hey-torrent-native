import { env } from "cloudflare:workers";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { AdminRequest } from "../src/admin";
import { NOW, bootstrapAdmin, call, invite, setNow, signIn, type SessionBody } from "./fakes";

// Les demandes sont écrites directement dans D1 : leur création par la
// watchlist est testée dans watchlist.test.ts.

let admin: SessionBody;
let member: SessionBody;
let other: SessionBody;

async function newMember(subject: string, name: string): Promise<SessionBody> {
  const code = await invite(admin.access_token);
  const response = await signIn("google", { subject, invitation: code, givenName: name });
  return response.json();
}

beforeAll(async () => {
  admin = await bootstrapAdmin();
  member = await newMember("membre-camille", "Camille");
  other = await newMember("membre-sam", "Sam");
});

const MOVIE_ID = "11111111-1111-4111-8111-111111111111";
const SERIES_ID = "22222222-2222-4222-8222-222222222222";
const UNKNOWN_ID = "99999999-9999-4999-8999-999999999999";

async function addRequest(id: string, mediaType: "movie" | "tv", tmdbId: number, status = "requested", createdAt = NOW) {
  await env.DB.prepare(
    `INSERT INTO requests (id, media_type, tmdb_id, title, poster_path, year, status, created_at, updated_at)
     VALUES (?, ?, ?, ?, '/p.jpg', 2020, ?, ?, ?)`,
  )
    .bind(id, mediaType, tmdbId, `Titre ${tmdbId}`, status, createdAt, createdAt)
    .run();
}

async function follow(session: SessionBody, requestId: string, mediaType: "movie" | "tv", tmdbId: number, seasons: number[] | null) {
  await env.DB.prepare(
    `INSERT INTO watchlist_items (user_id, media_type, tmdb_id, request_id, seasons, added_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(session.user.id, mediaType, tmdbId, requestId, seasons ? JSON.stringify(seasons) : null, NOW, NOW)
    .run();
}

beforeEach(async () => {
  setNow(NOW);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM watchlist_items"),
    env.DB.prepare("DELETE FROM requests"),
    env.DB.prepare("DELETE FROM credentials"),
  ]);
  await addRequest(MOVIE_ID, "movie", 603, "requested", NOW - 100);
  await addRequest(SERIES_ID, "tv", 95396, "requested", NOW - 50);
  await follow(member, MOVIE_ID, "movie", 603, null);
  await follow(member, SERIES_ID, "tv", 95396, [1]);
  await follow(other, SERIES_ID, "tv", 95396, [2, 3]);
});

const errorOf = async (response: Response) => ((await response.json()) as { error: string }).error;

async function queue(query = ""): Promise<AdminRequest[]> {
  const response = await call("GET", `/v1/admin/requests${query}`, undefined, admin.access_token);
  expect(response.status).toBe(200);
  return ((await response.json()) as { requests: AdminRequest[] }).requests;
}

describe("accès", () => {
  it("refuse toute route admin sans session", async () => {
    expect((await call("GET", "/v1/admin/requests")).status).toBe(401);
    expect((await call("GET", "/v1/admin/settings")).status).toBe(401);
  });

  it("refuse toute route admin à un membre", async () => {
    for (const [method, path] of [
      ["GET", "/v1/admin/requests"],
      ["GET", "/v1/admin/settings"],
      ["PUT", "/v1/admin/settings"],
      ["GET", "/v1/admin/invitations"],
      ["POST", `/v1/admin/requests/${MOVIE_ID}/refuse`],
    ] as const) {
      const body = method === "GET" ? undefined : method === "PUT" ? { settings: "{}" } : {};
      const response = await call(method, path, body, member.access_token);
      expect(response.status, `${method} ${path}`).toBe(403);
      expect(await errorOf(response)).toBe("forbidden");
    }
  });

  it("ne connaît pas les chemins inventés", async () => {
    expect((await call("GET", "/v1/admin/requests/pas-un-id/start", undefined, admin.access_token)).status).toBe(404);
    expect((await call("PATCH", "/v1/admin/settings", {}, admin.access_token)).status).toBe(404);
  });
});

describe("file des demandes", () => {
  it("liste les demandes à traiter, plus anciennes d'abord, avec leurs suiveurs", async () => {
    const requests = await queue();
    expect(requests.map((r) => r.id)).toEqual([MOVIE_ID, SERIES_ID]);
    const series = requests[1]!;
    expect(series.followers.map((f) => f.display_name).sort()).toEqual(["Camille", "Sam"]);
    expect(series.seasons).toEqual([1, 2, 3]);
    expect(requests[0]!.seasons).toBeNull();
  });

  it("une série voulue entière par quelqu'un se cherche entière", async () => {
    await env.DB.prepare("UPDATE watchlist_items SET seasons = NULL WHERE user_id = ? AND tmdb_id = 95396")
      .bind(other.user.id)
      .run();
    const series = (await queue()).find((r) => r.id === SERIES_ID)!;
    expect(series.seasons).toBeNull();
  });

  it("ignore les suiveurs qui ont retiré le titre", async () => {
    await env.DB.prepare("UPDATE watchlist_items SET deleted_at = ? WHERE user_id = ? AND tmdb_id = 95396")
      .bind(NOW, other.user.id)
      .run();
    const series = (await queue()).find((r) => r.id === SERIES_ID)!;
    expect(series.followers.map((f) => f.display_name)).toEqual(["Camille"]);
    expect(series.seasons).toEqual([1]);
  });

  it("filtre par état et refuse un état inconnu", async () => {
    await addRequest("33333333-3333-4333-8333-333333333333", "movie", 1, "available");
    expect((await queue()).length).toBe(2);
    expect((await queue("?status=available")).map((r) => r.tmdb_id)).toEqual([1]);
    expect((await queue("?status=all")).length).toBe(3);
    expect((await call("GET", "/v1/admin/requests?status=perdu", undefined, admin.access_token)).status).toBe(400);
  });
});

describe("traitement", () => {
  const action = (id: string, verb: string, body: unknown = {}) =>
    call("POST", `/v1/admin/requests/${id}/${verb}`, body, admin.access_token);

  it("envoie : la demande passe en cours avec le nom de la release", async () => {
    setNow(NOW + 60);
    const hash = "A".repeat(40);
    const response = await action(MOVIE_ID, "start", { release_title: "Film.2020.MULTi.1080p", torrent_hash: hash });
    expect(response.status).toBe(200);
    const { request } = (await response.json()) as { request: AdminRequest };
    expect(request.status).toBe("in_progress");
    expect(request.release_title).toBe("Film.2020.MULTi.1080p");
    expect(request.torrent_hash).toBe("a".repeat(40));
    expect(request.handled_at).toBe(NOW + 60);
    expect(request.updated_at).toBe(NOW + 60);
    // Le membre le voit dans sa watchlist.
    const list = await call("GET", "/v1/watchlist", undefined, member.access_token);
    const items = ((await list.json()) as { items: { tmdb_id: number; status: string }[] }).items;
    expect(items.find((i) => i.tmdb_id === 603)?.status).toBe("in_progress");
  });

  it("refuse avec un motif, puis rouvre", async () => {
    let response = await action(SERIES_ID, "refuse", { reason: "Pas disponible en français" });
    expect(response.status).toBe(200);
    let { request } = (await response.json()) as { request: AdminRequest };
    expect(request.status).toBe("refused");
    expect(request.refusal_reason).toBe("Pas disponible en français");
    response = await action(SERIES_ID, "reopen");
    ({ request } = (await response.json()) as { request: AdminRequest });
    expect(request.status).toBe("requested");
    expect(request.refusal_reason).toBeNull();
  });

  it("refuse sans motif", async () => {
    const response = await action(SERIES_ID, "refuse");
    expect(((await response.json()) as { request: AdminRequest }).request.refusal_reason).toBeNull();
  });

  it("une demande déjà disponible ne se traite plus : conflit", async () => {
    await env.DB.prepare("UPDATE requests SET status = 'available' WHERE id = ?").bind(MOVIE_ID).run();
    const response = await action(MOVIE_ID, "start", { release_title: "Film" });
    expect(response.status).toBe(409);
    expect(await errorOf(response)).toBe("conflict");
    expect((await action(MOVIE_ID, "refuse")).status).toBe(409);
    expect((await action(MOVIE_ID, "reopen")).status).toBe(409);
  });

  it("une demande disparue : introuvable", async () => {
    expect((await action(UNKNOWN_ID, "refuse")).status).toBe(404);
  });

  it("valide le corps", async () => {
    expect((await action(MOVIE_ID, "start", {})).status).toBe(400);
    expect((await action(MOVIE_ID, "start", { release_title: "x", torrent_hash: "zz" })).status).toBe(400);
    expect((await action(MOVIE_ID, "refuse", { reason: "x".repeat(201) })).status).toBe(400);
  });
});

describe("réglages chiffrés", () => {
  const settings = JSON.stringify({ sources: [{ id: "a", name: "Maison", url: "https://indexer.test/1/api", api_key: "secret-key" }] });

  it("garde les réglages chiffrés et les rend à l'administrateur", async () => {
    let response = await call("GET", "/v1/admin/settings", undefined, admin.access_token);
    expect(await response.json()).toEqual({ settings: null, updated_at: null });

    response = await call("PUT", "/v1/admin/settings", { settings }, admin.access_token);
    expect(response.status).toBe(200);

    const row = await env.DB.prepare("SELECT iv, ciphertext FROM credentials WHERE user_id = ?")
      .bind(admin.user.id)
      .first<{ iv: string; ciphertext: string }>();
    expect(row).not.toBeNull();
    expect(row!.ciphertext).not.toContain("secret-key");
    expect(row!.ciphertext).not.toContain("indexer");

    response = await call("GET", "/v1/admin/settings", undefined, admin.access_token);
    expect(await response.json()).toEqual({ settings, updated_at: NOW });
  });

  it("un texte chiffré déplacé sur un autre compte ne se déchiffre pas", async () => {
    await call("PUT", "/v1/admin/settings", { settings }, admin.access_token);
    const row = await env.DB.prepare("SELECT iv, ciphertext FROM credentials WHERE user_id = ?")
      .bind(admin.user.id)
      .first<{ iv: string; ciphertext: string }>();
    // Promu administrateur, un autre compte ne lit pas les réglages du premier.
    await env.DB.prepare("UPDATE users SET role = 'admin' WHERE id = ?").bind(other.user.id).run();
    await env.DB.prepare("INSERT INTO credentials (user_id, iv, ciphertext, updated_at) VALUES (?, ?, ?, ?)")
      .bind(other.user.id, row!.iv, row!.ciphertext, NOW)
      .run();
    const response = await call("GET", "/v1/admin/settings", undefined, other.access_token);
    expect(response.status).toBe(500);
    await env.DB.prepare("UPDATE users SET role = 'member' WHERE id = ?").bind(other.user.id).run();
  });

  it("sans clé de chiffrement configurée, rien n'est gardé", async () => {
    const { CREDENTIALS_KEY: _unused, ...withoutKey } = env;
    const response = await call("PUT", "/v1/admin/settings", { settings }, admin.access_token, withoutKey);
    expect(response.status).toBe(503);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM credentials").first("n")).toBe(0);
  });

  it("efface les réglages", async () => {
    await call("PUT", "/v1/admin/settings", { settings }, admin.access_token);
    expect((await call("DELETE", "/v1/admin/settings", undefined, admin.access_token)).status).toBe(204);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM credentials").first("n")).toBe(0);
  });

  it("refuse des réglages vides ou trop longs", async () => {
    expect((await call("PUT", "/v1/admin/settings", { settings: "" }, admin.access_token)).status).toBe(400);
    expect((await call("PUT", "/v1/admin/settings", { settings: "x".repeat(12_001) }, admin.access_token)).status).toBe(400);
  });

  it("disparaissent avec le compte", async () => {
    await env.DB.prepare("INSERT INTO credentials (user_id, iv, ciphertext, updated_at) VALUES (?, 'a', 'b', ?)")
      .bind(other.user.id, NOW)
      .run();
    await env.DB.prepare("DELETE FROM users WHERE id = ?").bind(other.user.id).run();
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM credentials WHERE user_id = ?").bind(other.user.id).first("n")).toBe(0);
    other = await newMember("membre-sam-bis", "Sam");
  });
});

describe("invitations", () => {
  interface InvitationRow {
    id: string;
    used_at: number | null;
    revoked_at: number | null;
    used_by_name: string | null;
  }
  const list = async () =>
    ((await (await call("GET", "/v1/admin/invitations", undefined, admin.access_token)).json()) as { invitations: InvitationRow[] })
      .invitations;

  it("liste les invitations avec la personne qui les a utilisées", async () => {
    const invitations = await list();
    expect(invitations.some((i) => i.used_by_name === "Camille")).toBe(true);
  });

  it("révoque une invitation inutilisée, qui ne sert plus", async () => {
    const code = await invite(admin.access_token);
    const fresh = (await list()).find((i) => i.used_at === null && i.revoked_at === null)!;
    expect((await call("DELETE", `/v1/admin/invitations/${fresh.id}`, undefined, admin.access_token)).status).toBe(204);
    const response = await signIn("google", { subject: "trop-tard", invitation: code });
    expect(response.status).toBe(403);
    // Une deuxième fois : déjà révoquée.
    expect((await call("DELETE", `/v1/admin/invitations/${fresh.id}`, undefined, admin.access_token)).status).toBe(409);
    expect((await call("DELETE", `/v1/admin/invitations/${UNKNOWN_ID}`, undefined, admin.access_token)).status).toBe(404);
  });
});
