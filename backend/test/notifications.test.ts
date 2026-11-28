import { env } from "cloudflare:workers";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { GROUPING_WINDOW } from "../src/availability";
import { readPlex, readTautulli } from "../src/webhooks";
import {
  NOW,
  bootstrapAdmin,
  call,
  fetchCalls,
  pushes,
  replyToFetch,
  replyToPush,
  send,
  setNow,
  signInMember,
  type SessionBody,
} from "./fakes";

// Aucun vrai service : Plex et Tautulli sont des requêtes fabriquées ici,
// TMDB passe par `replyToFetch`, les push par le faux expéditeur de fakes.ts.

const IOS_TOKEN = "a1".repeat(32);
const ANDROID_TOKEN = "fcm-token_ABCDEFGHIJKLMNOPQRSTUVWXYZ:0123456789";

let admin: SessionBody;
let counter = 0;

async function member(): Promise<SessionBody> {
  counter += 1;
  return signInMember("google", { subject: `notif-${counter}` });
}

async function errorOf(response: Response): Promise<string> {
  return ((await response.json()) as { error: string }).error;
}

/** Demande et entrée de watchlist, écrites directement (schéma de la migration 0003). */
async function follow(user: SessionBody, mediaType: "movie" | "tv", tmdbId: number, title: string, seasons?: number[]) {
  const requestId = `req-${mediaType}-${tmdbId}`;
  await env.DB.batch([
    env.DB.prepare(
      `INSERT OR IGNORE INTO requests (id, media_type, tmdb_id, title, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, 'in_progress', ?, ?)`,
    ).bind(requestId, mediaType, tmdbId, title, NOW, NOW),
    env.DB.prepare(
      `INSERT INTO watchlist_items (user_id, media_type, tmdb_id, request_id, seasons, added_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(user.user.id, mediaType, tmdbId, requestId, seasons ? JSON.stringify(seasons) : null, NOW, NOW),
  ]);
}

async function register(user: SessionBody, platform: "ios" | "android", token: string) {
  const response = await call("PUT", "/v1/devices", { platform, token }, user.access_token);
  expect(response.status).toBe(204);
}

async function webhookUrls(): Promise<{ plex_url: string; tautulli_url: string; tautulli_body: string }> {
  const response = await call("POST", "/v1/notifications/webhook", {}, admin.access_token);
  expect(response.status).toBe(201);
  return response.json();
}

/** Webhook Plex : formulaire multipart avec le champ `payload`, comme Plex l'envoie. */
function plexHook(url: string, payload: unknown): Promise<Response> {
  const form = new FormData();
  form.set("payload", JSON.stringify(payload));
  return send(new Request(url, { method: "POST", body: form }));
}

function tautulliHook(url: string, body: unknown): Promise<Response> {
  return send(new Request(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }));
}

async function statusOf(response: Response): Promise<string> {
  expect(response.status).toBe(200);
  return ((await response.json()) as { status: string }).status;
}

function plexMovie(guids: string[], title = "Matrix") {
  return { event: "library.new", Metadata: { type: "movie", title, Guid: guids.map((id) => ({ id })) } };
}

function plexEpisode(season: number, episode: number, guids: string[]) {
  return {
    event: "library.new",
    Metadata: {
      type: "episode",
      title: `Épisode ${episode}`,
      grandparentTitle: "Severance",
      parentIndex: season,
      index: episode,
      Guid: guids.map((id) => ({ id })),
    },
  };
}

async function requestStatus(mediaType: string, tmdbId: number): Promise<string | undefined> {
  const row = await env.DB.prepare("SELECT status FROM requests WHERE media_type = ? AND tmdb_id = ?")
    .bind(mediaType, tmdbId)
    .first<{ status: string }>();
  return row?.status;
}

beforeAll(async () => {
  admin = await bootstrapAdmin();
});

beforeEach(async () => {
  setNow(NOW);
  fetchCalls.length = 0;
  pushes.length = 0;
  replyToPush(() => "sent");
  replyToFetch(() => new Response("{}", { status: 404 }));
});

describe("appareils", () => {
  it("exige une session et un jeton bien formé", async () => {
    expect((await call("PUT", "/v1/devices", { platform: "ios", token: IOS_TOKEN })).status).toBe(401);
    const user = await member();
    for (const body of [{ platform: "ios", token: "pas-hexa" }, { platform: "web", token: IOS_TOKEN }, { platform: "android" }]) {
      expect(await errorOf(await call("PUT", "/v1/devices", body, user.access_token))).toBe("invalid_request");
    }
  });

  it("garde un seul jeton par session et le passe au dernier compte", async () => {
    const first = await member();
    await register(first, "android", `${ANDROID_TOKEN}-1`);
    await register(first, "android", `${ANDROID_TOKEN}-2`);
    const second = await member();
    await register(second, "android", `${ANDROID_TOKEN}-2`);
    const { results } = await env.DB.prepare("SELECT token, user_id FROM devices WHERE token LIKE ?")
      .bind(`${ANDROID_TOKEN}-%`)
      .all<{ token: string; user_id: string }>();
    expect(results).toEqual([{ token: `${ANDROID_TOKEN}-2`, user_id: second.user.id }]);
  });

  it("oublie l'appareil de la session", async () => {
    const user = await member();
    await register(user, "ios", IOS_TOKEN.replace("a1", "b2"));
    expect((await call("DELETE", "/v1/devices", undefined, user.access_token)).status).toBe(204);
    const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ?").bind(user.user.id).first<{ n: number }>();
    expect(row?.n).toBe(0);
  });

  it("envoie une push d'essai à ses propres appareils", async () => {
    const user = await member();
    await register(user, "android", `${ANDROID_TOKEN}-essai`);
    const response = await call("POST", "/v1/notifications/test", {}, user.access_token);
    expect(await response.json()).toEqual({ devices: 1, sent: 1 });
    expect(pushes[0]?.message.bodyKey).toBe("push_test");
  });
});

describe("réglage du webhook", () => {
  it("est réservé à l'administrateur", async () => {
    const user = await member();
    expect(await errorOf(await call("POST", "/v1/notifications/webhook", {}, user.access_token))).toBe("forbidden");
    expect(await errorOf(await call("GET", "/v1/notifications/webhook", undefined, user.access_token))).toBe("forbidden");
  });

  it("donne les URL une fois et révoque les anciennes", async () => {
    const old = await webhookUrls();
    const fresh = await webhookUrls();
    expect(fresh.plex_url).toMatch(/^https:\/\/api\.test\/v1\/hooks\/plex\/[A-Za-z0-9_-]{43}$/);
    expect(fresh.tautulli_body).toContain("{themoviedb_id}");
    expect(await errorOf(await plexHook(old.plex_url, plexMovie(["tmdb://1"])))).toBe("not_found");
    expect(await statusOf(await plexHook(fresh.plex_url, plexMovie(["tmdb://1"])))).not.toBe("");
    const status = (await (await call("GET", "/v1/notifications/webhook", undefined, admin.access_token)).json()) as {
      configured: boolean;
      last_used_at: number | null;
    };
    expect(status).toMatchObject({ configured: true, last_used_at: NOW });
    expect(JSON.stringify(status)).not.toContain("hooks");
  });

  it("refuse un jeton inconnu sans détail", async () => {
    const response = await plexHook("https://api.test/v1/hooks/plex/inconnu-inconnu-inconnu-inconnu", plexMovie(["tmdb://1"]));
    expect(response.status).toBe(404);
    expect(await errorOf(response)).toBe("not_found");
  });
});

describe("arrivée d'un film", () => {
  it("passe la demande en disponible et prévient chaque personne qui l'attend", async () => {
    const urls = await webhookUrls();
    const alice = await member();
    const bob = await member();
    const other = await member();
    await follow(alice, "movie", 603, "Matrix");
    await follow(bob, "movie", 603, "Matrix");
    await register(alice, "ios", IOS_TOKEN);
    await register(bob, "android", ANDROID_TOKEN);
    await register(other, "android", `${ANDROID_TOKEN}-autre`);

    expect(await statusOf(await plexHook(urls.plex_url, plexMovie(["imdb://tt0133093", "tmdb://603"])))).toBe("notified");
    expect(await requestStatus("movie", 603)).toBe("available");
    expect(pushes.map((p) => p.token).sort()).toEqual([ANDROID_TOKEN, IOS_TOKEN].sort());
    expect(pushes[0]?.message).toMatchObject({ title: "Matrix", bodyKey: "push_available_movie", mediaType: "movie", tmdbId: 603 });

    // Tautulli rattrape le même ajout : aucune deuxième push.
    pushes.length = 0;
    const again = await tautulliHook(urls.tautulli_url, { event: "recently_added", media_type: "movie", title: "Matrix", tmdb_id: "603" });
    expect(await statusOf(again)).toBe("duplicate");
    expect(pushes).toHaveLength(0);
    const library = await env.DB.prepare("SELECT COUNT(*) AS n FROM library WHERE media_type = 'movie' AND tmdb_id = 603").first<{ n: number }>();
    expect(library?.n).toBe(1);
  });

  it("retrouve un film par son identifiant IMDb", async () => {
    const urls = await webhookUrls();
    const user = await member();
    await follow(user, "movie", 438631, "Dune");
    await register(user, "android", `${ANDROID_TOKEN}-dune`);
    replyToFetch((url) =>
      url.includes("/3/find/tt1160419") ? Response.json({ movie_results: [{ id: 438631 }] }) : new Response("{}", { status: 404 }),
    );
    const legacy = { event: "library.new", Metadata: { type: "movie", title: "Dune", guid: "com.plexapp.agents.imdb://tt1160419?lang=fr" } };
    expect(await statusOf(await plexHook(urls.plex_url, legacy))).toBe("notified");
    expect(fetchCalls[0]?.url).toContain("external_source=imdb_id");
    expect(pushes).toHaveLength(1);
  });

  it("note un film que personne n'a demandé, sans push", async () => {
    const urls = await webhookUrls();
    expect(await statusOf(await plexHook(urls.plex_url, plexMovie(["tmdb://999001"])))).toBe("no_request");
    expect(pushes).toHaveLength(0);
  });

  it("oublie un appareil dont le jeton est périmé", async () => {
    const urls = await webhookUrls();
    const user = await member();
    await follow(user, "movie", 680, "Pulp Fiction");
    await register(user, "ios", IOS_TOKEN.replace(/a/g, "c"));
    replyToPush(() => "gone");
    expect(await statusOf(await plexHook(urls.plex_url, plexMovie(["tmdb://680"])))).toBe("notified");
    const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE user_id = ?").bind(user.user.id).first<{ n: number }>();
    expect(row?.n).toBe(0);
  });

  it("ne prévient pas une session coupée", async () => {
    const urls = await webhookUrls();
    const user = await member();
    await follow(user, "movie", 13, "Forrest Gump");
    await register(user, "android", `${ANDROID_TOKEN}-coupee`);
    await call("POST", "/v1/auth/sign-out", { refresh_token: user.refresh_token });
    expect(await statusOf(await plexHook(urls.plex_url, plexMovie(["tmdb://13"])))).toBe("notified");
    expect(pushes).toHaveLength(0);
  });

  it("ignore les autres événements et les charges illisibles", async () => {
    const urls = await webhookUrls();
    expect(await statusOf(await plexHook(urls.plex_url, { event: "media.play", Metadata: { type: "movie" } }))).toBe("ignored");
    const garbage = new FormData();
    garbage.set("payload", "{pas du json");
    expect(await statusOf(await send(new Request(urls.plex_url, { method: "POST", body: garbage })))).toBe("ignored");
    expect(await statusOf(await plexHook(urls.plex_url, plexMovie(["tmdb://abc"])))).toBe("unknown_media");
  });
});

describe("arrivée d'épisodes", () => {
  const FIND_EPISODE = (season: number, episode: number) =>
    Response.json({ tv_episode_results: [{ show_id: 95396, season_number: season, episode_number: episode }] });

  it("retrouve la série d'un épisode Plex et ne prévient que pour les saisons voulues", async () => {
    const urls = await webhookUrls();
    const wholeShow = await member();
    const seasonTwo = await member();
    const seasonOne = await member();
    await follow(wholeShow, "tv", 95396, "Severance");
    await follow(seasonTwo, "tv", 95396, "Severance", [2]);
    await follow(seasonOne, "tv", 95396, "Severance", [1]);
    await register(wholeShow, "android", `${ANDROID_TOKEN}-tout`);
    await register(seasonTwo, "android", `${ANDROID_TOKEN}-s2`);
    await register(seasonOne, "android", `${ANDROID_TOKEN}-s1`);
    replyToFetch((url) => (url.includes("/3/find/9000201") ? FIND_EPISODE(2, 1) : new Response("{}", { status: 404 })));

    // L'identifiant tmdb:// d'un épisode Plex est celui de l'épisode : il est ignoré.
    const response = await plexHook(urls.plex_url, plexEpisode(2, 1, ["tmdb://5550001", "tvdb://9000201"]));
    expect(await statusOf(response)).toBe("notified");
    // Un épisode ne suffit pas : la série n'est pas encore « disponible ».
    expect(await requestStatus("tv", 95396)).toBe("in_progress");
    expect(pushes.map((p) => p.token).sort()).toEqual([`${ANDROID_TOKEN}-s2`, `${ANDROID_TOKEN}-tout`]);
    expect(pushes[0]?.message).toMatchObject({ title: "Severance", bodyKey: "push_available_episode", bodyArgs: ["2", "1"] });

    // L'épisode suivant de la même saison, peu après : regroupé, pas de deuxième push.
    pushes.length = 0;
    replyToFetch((url) => (url.includes("/3/find/9000202") ? FIND_EPISODE(2, 2) : new Response("{}", { status: 404 })));
    setNow(NOW + 600);
    expect(await statusOf(await plexHook(urls.plex_url, plexEpisode(2, 2, ["tvdb://9000202"])))).toBe("grouped");
    // Tautulli annonce la saison entière : déjà prévenu, regroupé aussi.
    const season = { event: "recently_added", media_type: "season", show_name: "Severance", season: "2", tmdb_id: "95396" };
    expect(await statusOf(await tautulliHook(urls.tautulli_url, season))).toBe("grouped");
    expect(pushes).toHaveLength(0);

    // Une semaine plus tard, l'épisode 3 fait l'objet d'une nouvelle push.
    replyToFetch((url) => (url.includes("/3/find/9000203") ? FIND_EPISODE(2, 3) : new Response("{}", { status: 404 })));
    setNow(NOW + GROUPING_WINDOW + 7 * 24 * 3600);
    expect(await statusOf(await plexHook(urls.plex_url, plexEpisode(2, 3, ["tvdb://9000203"])))).toBe("notified");
    expect(pushes).toHaveLength(2);
  });

  it("accepte l'épisode Tautulli avec l'identifiant TMDB de la série", async () => {
    const urls = await webhookUrls();
    const user = await member();
    await follow(user, "tv", 1399, "Game of Thrones");
    await register(user, "ios", IOS_TOKEN.replace(/1/g, "d"));
    const body = {
      event: "recently_added",
      media_type: "episode",
      title: "L'hiver vient",
      show_name: "Game of Thrones",
      season: "1",
      episode: "1",
      tmdb_id: "1399",
      tvdb_id: "",
      imdb_id: "",
    };
    expect(await statusOf(await tautulliHook(urls.tautulli_url, body))).toBe("notified");
    expect(fetchCalls.filter((c) => c.url.includes("/3/find/"))).toHaveLength(0);
    expect(pushes[0]?.message).toMatchObject({ title: "Game of Thrones", bodyArgs: ["1", "1"] });
  });
});

describe("disponibilité d'une série", () => {
  // Saison 1 : 2 épisodes ; saison 2 : 2 diffusés sur 3 ; saison 3 annoncée.
  const SHOW = 4242;
  const DETAIL = {
    id: SHOW,
    name: "La Série",
    first_air_date: "2024-01-01",
    seasons: [
      { season_number: 0, episode_count: 4 },
      { season_number: 1, episode_count: 2 },
      { season_number: 2, episode_count: 3 },
      { season_number: 3, episode_count: 8 },
    ],
    last_episode_to_air: { season_number: 2, episode_number: 2 },
  };
  const tmdb = (url: string) => (url.includes(`/3/tv/${SHOW}?`) ? Response.json(DETAIL) : new Response("{}", { status: 404 }));
  const episode = (season: number, episode: number) => ({
    event: "recently_added",
    media_type: "episode",
    show_name: "La Série",
    season: String(season),
    episode: String(episode),
    tmdb_id: String(SHOW),
  });

  beforeEach(async () => {
    await env.DB.prepare("DELETE FROM notified WHERE tmdb_id = ?").bind(SHOW).run();
    await env.DB.prepare("DELETE FROM watchlist_items WHERE tmdb_id = ?").bind(SHOW).run();
    await env.DB.prepare("DELETE FROM requests WHERE tmdb_id = ?").bind(SHOW).run();
    await env.DB.prepare("DELETE FROM tmdb_cache WHERE cache_key LIKE ?").bind(`tv/${SHOW}?%`).run();
    replyToFetch(tmdb);
  });

  it("attend chaque épisode diffusé des saisons voulues", async () => {
    const urls = await webhookUrls();
    await follow(await member(), "tv", SHOW, "La Série", [1]);
    await tautulliHook(urls.tautulli_url, episode(1, 1));
    expect(await requestStatus("tv", SHOW)).toBe("in_progress");
    await tautulliHook(urls.tautulli_url, episode(1, 2));
    expect(await requestStatus("tv", SHOW)).toBe("available");
  });

  it("série entière : toutes les saisons commencées, sans les spéciaux ni la saison à venir", async () => {
    const urls = await webhookUrls();
    await follow(await member(), "tv", SHOW, "La Série");
    for (const [s, e] of [[1, 1], [1, 2], [2, 1]] as const) await tautulliHook(urls.tautulli_url, episode(s, e));
    expect(await requestStatus("tv", SHOW)).toBe("in_progress");
    await tautulliHook(urls.tautulli_url, episode(2, 2));
    expect(await requestStatus("tv", SHOW)).toBe("available");
  });

  it("une saison annoncée en entier compte d'un coup", async () => {
    const urls = await webhookUrls();
    await follow(await member(), "tv", SHOW, "La Série", [2]);
    const season = { event: "recently_added", media_type: "season", show_name: "La Série", season: "2", tmdb_id: String(SHOW) };
    await tautulliHook(urls.tautulli_url, season);
    expect(await requestStatus("tv", SHOW)).toBe("available");
  });

  it("TMDB injoignable : l'état ne change pas", async () => {
    const urls = await webhookUrls();
    await follow(await member(), "tv", SHOW, "La Série", [1]);
    replyToFetch(() => new Response("{}", { status: 500 }));
    await tautulliHook(urls.tautulli_url, episode(1, 1));
    await tautulliHook(urls.tautulli_url, episode(1, 2));
    expect(await requestStatus("tv", SHOW)).toBe("in_progress");
  });

  it("rouvre une série disponible quand quelqu'un veut une saison absente", async () => {
    const urls = await webhookUrls();
    const first = await member();
    await follow(first, "tv", SHOW, "La Série", [1]);
    await tautulliHook(urls.tautulli_url, episode(1, 1));
    await tautulliHook(urls.tautulli_url, episode(1, 2));
    expect(await requestStatus("tv", SHOW)).toBe("available");

    // Vouloir la saison 1, déjà là : rien ne change.
    const second = await member();
    let response = await call("PUT", `/v1/watchlist/tv/${SHOW}`, { seasons: [1] }, second.access_token);
    expect(response.status).toBe(200);
    expect(await requestStatus("tv", SHOW)).toBe("available");

    // Vouloir la saison 2, pas encore sur Plex : retour dans la file.
    response = await call("PUT", `/v1/watchlist/tv/${SHOW}`, { seasons: [2] }, second.access_token);
    expect(((await response.json()) as { item: { status: string } }).item.status).toBe("requested");
    expect(await requestStatus("tv", SHOW)).toBe("requested");
  });
});

describe("lecture des charges", () => {
  it("lit l'ancien format des agents Plex pour un épisode", () => {
    const sighting = readPlex({
      event: "library.new",
      Metadata: { type: "episode", grandparentTitle: "Lost", parentIndex: 1, index: 3, guid: "com.plexapp.agents.thetvdb://73739/1/3?lang=fr" },
    });
    expect(sighting).toMatchObject({ kind: "episode", title: "Lost", tvdb: 73739, season: 1, episode: 3, episodeIds: false });
  });

  it("ignore un corps Tautulli d'un autre événement ou d'un type inconnu", () => {
    expect(readTautulli({ event: "playback_start", media_type: "movie" })).toBeNull();
    expect(readTautulli({ media_type: "track" })).toBeNull();
    expect(readTautulli("texte")).toBeNull();
  });
});
