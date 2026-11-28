import { env } from "cloudflare:workers";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { MAX_PER_RUN, SEARCH_INTERVAL, STALE_AFTER, runAutomation, shortlist } from "../src/autosearch";
import { RECOMMEND_MODEL } from "../src/claude";
import { containsWord, parseFeed } from "../src/torznab";
import { NOW, bootstrapAdmin, call, deps, fetchCalls, pushes, replyToFetch, setNow, signInMember, type SessionBody } from "./fakes";

// Sources, Claude, TMDB et Plex sont simulés : adresses en `.test`, clés factices.

const SOURCE = { id: "src-1", name: "Ma source", url: "https://source.test/1", api_key: "cle-source", enabled: true };
const PLEX = { url: "https://plex.test", token: "jeton-plex" };
const ANDROID_TOKEN = "fcm-token_ABCDEFGHIJKLMNOPQRSTUVWXYZ:0123456789";

let admin: SessionBody;
let member: SessionBody;

beforeAll(async () => {
  admin = await bootstrapAdmin();
  member = await signInMember("google", { subject: "membre-auto" });
  const response = await call("PUT", "/v1/devices", { platform: "android", token: ANDROID_TOKEN }, admin.access_token);
  expect(response.status).toBe(204);
});

async function saveSettings(settings: Record<string, unknown>) {
  const response = await call("PUT", "/v1/admin/settings", { settings: JSON.stringify(settings) }, admin.access_token);
  expect(response.status).toBe(200);
}

const AUTO = { sources: [SOURCE], anthropic_api_key: "cle-de-test", release_rules: "MULTi, 15 Go maximum" };

function rss(items: { title: string; size?: number; seeders?: number }[]): Response {
  const body = items
    .map(
      (i) => `<item><title><![CDATA[${i.title}]]></title><size>${i.size ?? 1_000_000_000}</size>
        <link>https://source.test/dl?secret=1</link>
        <torznab:attr name="seeders" value="${i.seeders ?? 10}"/></item>`,
    )
    .join("");
  return new Response(`<?xml version="1.0"?><rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>${body}</channel></rss>`);
}

function claudeChoice(choice: string, reason: string): Response {
  return Response.json({
    id: "msg_test",
    type: "message",
    role: "assistant",
    model: RECOMMEND_MODEL,
    content: [{ type: "text", text: JSON.stringify({ choice, reason }) }],
    stop_reason: "end_turn",
    stop_sequence: null,
    usage: { input_tokens: 500, output_tokens: 40 },
  });
}

interface World {
  tmdb?: Record<string, unknown>;
  feed?: () => Response;
  claude?: () => Response;
  plex?: (url: URL) => Response;
}

function world(w: World) {
  replyToFetch((raw) => {
    const url = new URL(raw);
    if (url.hostname === "api.themoviedb.org") return Response.json(w.tmdb ?? { release_date: "2020-01-01" });
    if (url.hostname === "source.test") return w.feed ? w.feed() : rss([]);
    if (url.hostname === "api.anthropic.com") return w.claude ? w.claude() : new Response("{}", { status: 500 });
    if (url.hostname === "plex.test" && w.plex) return w.plex(url);
    return new Response("{}", { status: 404 });
  });
}

const callsTo = (host: string) => fetchCalls.filter((c) => new URL(c.url).hostname === host);
const run = (now = NOW) => runAutomation(env, deps.fetch, deps.push(env), now);

async function request(id: string, mediaType: "movie" | "tv", tmdbId: number, title: string, createdAt = NOW) {
  await env.DB.prepare(
    `INSERT INTO requests (id, media_type, tmdb_id, title, year, status, created_at, updated_at, waiting_since)
     VALUES (?1, ?2, ?3, ?4, 2024, 'requested', ?5, ?5, ?5)`,
  )
    .bind(id, mediaType, tmdbId, title, createdAt)
    .run();
}

const row = (id: string) =>
  env.DB.prepare("SELECT status, candidate_title, candidate_reason, auto_checked_at, stale_notified_at FROM requests WHERE id = ?")
    .bind(id)
    .first<{ status: string; candidate_title: string | null; candidate_reason: string | null; auto_checked_at: number | null; stale_notified_at: number | null }>();

async function openIds(): Promise<string[]> {
  const response = await call("GET", "/v1/admin/requests", undefined, admin.access_token);
  return ((await response.json()) as { requests: { id: string }[] }).requests.map((r) => r.id);
}

beforeEach(async () => {
  fetchCalls.length = 0;
  pushes.length = 0;
  setNow(NOW);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM watchlist_items"),
    env.DB.prepare("DELETE FROM requests"),
    env.DB.prepare("DELETE FROM credentials"),
    env.DB.prepare("DELETE FROM tmdb_cache"),
    env.DB.prepare("DELETE FROM library"),
    env.DB.prepare("DELETE FROM notified"),
    env.DB.prepare(
      "UPDATE automation SET searched_at = NULL, source_reports = NULL, assistant_status = NULL, plex_synced_at = NULL, plex_status = NULL, plex_items = NULL",
    ),
  ]);
});

describe("lecture Torznab", () => {
  it("lit noms, tailles, sources et indexeur, sans garder les liens", () => {
    const items = parseFeed(`<rss><channel>
      <item><title>Film &amp; Cie 2024 1080p</title><enclosure url="https://x.test/a" length="2000"/>
        <torznab:attr name="seeders" value="12"/><prowlarrindexer>Indexeur</prowlarrindexer></item>
      <item><title><![CDATA[Autre <b>]]></title><size>5</size></item>
      <item><description>sans titre</description></item>
    </channel></rss>`);
    expect(items).toEqual([
      { title: "Film & Cie 2024 1080p", size_bytes: 2000, seeders: 12, indexer: "Indexeur" },
      { title: "Autre <b>", size_bytes: 5, seeders: undefined, indexer: undefined },
    ]);
    expect(JSON.stringify(items)).not.toContain("x.test");
  });

  it("traduit les erreurs Torznab et refuse un document illisible", () => {
    expect(() => parseFeed('<error code="100" description="Clé"/>')).toThrow("auth_failed");
    expect(() => parseFeed('<error code="429"/>')).toThrow("rate_limited");
    expect(() => parseFeed("<html>")).toThrow("invalid_response");
  });

  it("présélection : sans source morte, terme exclu ni doublon, les mieux partagées d'abord", () => {
    const list = shortlist(
      [
        { title: "Film.2024.CAM", seeders: 90 },
        { title: "Film.2024.1080p", seeders: 5 },
        { title: "film.2024.1080p", seeders: 7 },
        { title: "Film.2024.2160p", seeders: 50 },
        { title: "Film.2024.720p", seeders: 0 },
      ],
      ["cam"],
    );
    expect(list.map((c) => [c.id, c.title])).toEqual([
      ["c0", "Film.2024.2160p"],
      ["c1", "Film.2024.1080p"],
    ]);
    expect(containsWord("Film.Camera.2024", "cam")).toBe(false);
  });
});

describe("recherche automatique", () => {
  it("trouve une release, la garde avec sa raison et la met « à traiter »", async () => {
    await saveSettings(AUTO);
    await request("r-film", "movie", 101, "Le Film");
    world({
      tmdb: { release_date: "2024-03-01", original_title: "The Film" },
      feed: () => rss([{ title: "Le.Film.2024.MULTi.1080p", seeders: 30 }]),
      claude: () => claudeChoice("c0", "MULTi en 1080p, petite taille."),
    });
    // Sans release trouvée, la demande n'est pas « à traiter ».
    expect(await openIds()).toEqual([]);

    await run();
    expect(await row("r-film")).toMatchObject({ candidate_title: "Le.Film.2024.MULTi.1080p", candidate_reason: "MULTi en 1080p, petite taille." });
    expect(await openIds()).toEqual(["r-film"]);
    // Titre français et titre original, chacun cherché une fois.
    const searches = callsTo("source.test").map((c) => new URL(c.url));
    expect(searches.map((u) => u.searchParams.get("q"))).toEqual(["Le Film", "The Film"]);
    expect(searches[0]?.pathname).toBe("/1/api");
    expect(searches[0]?.searchParams.get("t")).toBe("movie");
    expect(callsTo("api.anthropic.com")).toHaveLength(1);

    // Une demande servie n'est plus cherchée.
    fetchCalls.length = 0;
    await run(NOW + SEARCH_INTERVAL + 1);
    expect(callsTo("source.test")).toHaveLength(0);

    const status = (await (await call("GET", "/v1/admin/automation", undefined, admin.access_token)).json()) as Record<string, unknown>;
    expect(status).toMatchObject({ searched_at: NOW, sources: [{ id: "src-1", failure: null }], assistant_status: "ok" });
    expect(JSON.stringify(status)).not.toContain("source.test");
  });

  it("« aucune » : recherche reprise dans 6 h, sans rappeler Claude si rien n'a changé", async () => {
    await saveSettings(AUTO);
    await request("r-none", "movie", 102, "Rien");
    world({ feed: () => rss([{ title: "Rien.2024.VOSTFR" }]), claude: () => claudeChoice("none", "Pas de version française.") });
    await run();
    expect(await row("r-none")).toMatchObject({ candidate_title: null, auto_checked_at: NOW });
    expect(callsTo("api.anthropic.com")).toHaveLength(1);

    fetchCalls.length = 0;
    await run(NOW + 60);
    expect(fetchCalls).toHaveLength(0);

    await run(NOW + SEARCH_INTERVAL);
    expect(callsTo("source.test").length).toBeGreaterThan(0);
    expect(callsTo("api.anthropic.com")).toHaveLength(0);

    world({ feed: () => rss([{ title: "Rien.2024.VOSTFR" }, { title: "Rien.2024.MULTi" }]), claude: () => claudeChoice("c1", "MULTi.") });
    fetchCalls.length = 0;
    await run(NOW + 2 * SEARCH_INTERVAL);
    expect(callsTo("api.anthropic.com")).toHaveLength(1);
  });

  it("un film pas encore sorti n'est pas cherché", async () => {
    await saveSettings(AUTO);
    await request("r-futur", "movie", 103, "Bientôt");
    world({ tmdb: { release_date: "2030-01-01" } });
    await run();
    expect(callsTo("source.test")).toHaveLength(0);
    expect((await row("r-futur"))?.auto_checked_at).toBe(NOW);
  });

  it("série : la saison voulue par tous est cherchée seule", async () => {
    await saveSettings(AUTO);
    await request("r-serie", "tv", 201, "La Série");
    await env.DB.prepare(
      `INSERT INTO watchlist_items (user_id, media_type, tmdb_id, request_id, seasons, added_at, updated_at)
       VALUES (?, 'tv', 201, 'r-serie', '[2]', ?, ?)`,
    )
      .bind(member.user.id, NOW, NOW)
      .run();
    world({ tmdb: { name: "La Série" } });
    await run();
    const search = new URL(callsTo("source.test")[0]!.url);
    expect(search.searchParams.get("t")).toBe("tvsearch");
    expect(search.searchParams.get("season")).toBe("2");
  });

  it("note la source en échec et s'arrête quand la clé Anthropic est refusée", async () => {
    await saveSettings({ ...AUTO, sources: [SOURCE, { ...SOURCE, id: "src-2", url: "https://autre.test/2" }] });
    await request("r-a", "movie", 104, "A", NOW - 10);
    await request("r-b", "movie", 105, "B");
    world({
      feed: () => rss([{ title: "A.2024.1080p" }]),
      claude: () => Response.json({ type: "error", error: { type: "authentication_error", message: "clé" } }, { status: 401 }),
    });
    await run();
    // Claude refusé : la seconde demande n'est pas cherchée.
    expect(callsTo("api.anthropic.com")).toHaveLength(1);
    expect((await row("r-b"))?.auto_checked_at).toBeNull();
    const status = (await (await call("GET", "/v1/admin/automation", undefined, admin.access_token)).json()) as {
      sources: { id: string; failure: string | null }[];
      assistant_status: string;
    };
    expect(status.assistant_status).toBe("auth_failed");
    expect(status.sources).toContainEqual({ id: "src-2", failure: "invalid_response" });
  });

  it(`au plus ${MAX_PER_RUN} demandes par passage`, async () => {
    await saveSettings(AUTO);
    for (let i = 0; i < MAX_PER_RUN + 3; i++) await request(`r-${i}`, "movie", 300 + i, `Titre ${i}`);
    world({});
    await run();
    const { n } = (await env.DB.prepare("SELECT COUNT(*) AS n FROM requests WHERE auto_checked_at IS NOT NULL").first<{ n: number }>())!;
    expect(n).toBe(MAX_PER_RUN);
  });

  it("sans clé Anthropic ni source active, rien ne tourne et tout reste « à traiter »", async () => {
    await saveSettings({ sources: [{ ...SOURCE, enabled: false }], anthropic_api_key: "cle-de-test" });
    await request("r-off", "movie", 106, "Off");
    world({});
    await run();
    expect(fetchCalls).toHaveLength(0);
    expect(await openIds()).toEqual(["r-off"]);
  });

  it("prévient l'administrateur une fois après 3 semaines sans release", async () => {
    await saveSettings(AUTO);
    await request("r-vieux", "movie", 107, "Vieux", NOW - STALE_AFTER);
    await request("r-jeune", "movie", 108, "Jeune", NOW - STALE_AFTER + 3600);
    world({});
    await run();
    expect(pushes).toHaveLength(1);
    expect(pushes[0]?.message).toMatchObject({ bodyKey: "push_requests_stale", bodyArgs: ["1"] });
    expect((await row("r-vieux"))?.stale_notified_at).toBe(NOW);
    pushes.length = 0;
    await run(NOW + 60);
    expect(pushes).toHaveLength(0);
  });

  const REOPEN_ID = "0b0b0b0b-1111-4222-8333-444444444444";
  it("une réouverture efface la release trouvée et relance l'attente", async () => {
    await saveSettings(AUTO);
    await request(REOPEN_ID, "movie", 109, "Re");
    await env.DB.prepare(
      "UPDATE requests SET status = 'in_progress', candidate_title = 'X', candidate_reason = 'Y', stale_notified_at = 1 WHERE id = ?",
    )
      .bind(REOPEN_ID)
      .run();
    setNow(NOW + 100);
    const response = await call("POST", `/v1/admin/requests/${REOPEN_ID}/reopen`, {}, admin.access_token);
    expect(response.status).toBe(200);
    expect(await row(REOPEN_ID)).toMatchObject({ status: "requested", candidate_title: null, candidate_reason: null, stale_notified_at: null });
  });
});

describe("connexion Plex", () => {
  const plex = (url: URL): Response => {
    if (url.searchParams.has("X-Plex-Token")) return new Response("jeton dans l'URL", { status: 400 });
    switch (url.pathname) {
      case "/library/sections":
        return Response.json({
          MediaContainer: {
            Directory: [
              { key: "1", type: "movie" },
              { key: "2", type: "show" },
              { key: "3", type: "artist" },
            ],
          },
        });
      case "/library/sections/1/all":
        return Response.json({
          MediaContainer: { Metadata: [{ ratingKey: "11", title: "Matrix", Guid: [{ id: "tmdb://603" }] }, { ratingKey: "12", title: "Sans guid" }] },
        });
      case "/library/sections/2/all":
        return Response.json({ MediaContainer: { Metadata: [{ ratingKey: "21", title: "La Série", Guid: [{ id: "tmdb://4242" }] }] } });
      case "/library/metadata/21/allLeaves":
        return Response.json({
          MediaContainer: {
            Metadata: [
              { parentIndex: 1, index: 1 },
              { parentIndex: 1, index: 2 },
              { parentIndex: 2, index: 1 },
            ],
          },
        });
      default:
        return new Response("{}", { status: 404 });
    }
  };
  const SHOW = {
    id: 4242,
    name: "La Série",
    seasons: [
      { season_number: 1, episode_count: 2 },
      { season_number: 2, episode_count: 3 },
    ],
    last_episode_to_air: { season_number: 2, episode_number: 3 },
  };

  it("teste la connexion depuis Serveurs", async () => {
    world({ plex });
    let response = await call("POST", "/v1/admin/plex/test", PLEX, admin.access_token);
    expect(await response.json()).toEqual({ sections: 2 });
    expect(callsTo("plex.test")[0]?.headers["x-plex-token"]).toBe("jeton-plex");

    world({ plex: () => new Response("", { status: 401 }) });
    response = await call("POST", "/v1/admin/plex/test", PLEX, admin.access_token);
    expect(((await response.json()) as { error: string }).error).toBe("plex_auth_failed");

    response = await call("POST", "/v1/admin/plex/test", { ...PLEX, url: "http://plex.test" }, admin.access_token);
    expect(((await response.json()) as { error: string }).error).toBe("plex_unreachable");
    expect((await call("POST", "/v1/admin/plex/test", PLEX, member.access_token)).status).toBe(403);
  });

  it("passe « disponible » ce qui est déjà sur Plex, saisons complètes seulement", async () => {
    await saveSettings({ plex: PLEX });
    await request("r-matrix", "movie", 603, "Matrix");
    await request("r-serie", "tv", 4242, "La Série");
    await env.DB.prepare(
      `INSERT INTO watchlist_items (user_id, media_type, tmdb_id, request_id, seasons, added_at, updated_at)
       VALUES (?, 'tv', 4242, 'r-serie', '[1]', ?, ?)`,
    )
      .bind(member.user.id, NOW, NOW)
      .run();
    replyToFetch((raw) => {
      const url = new URL(raw);
      if (url.hostname === "api.themoviedb.org") return Response.json(SHOW);
      return plex(url);
    });
    await run();
    expect((await row("r-matrix"))?.status).toBe("available");
    // Saison 1 complète, la seule voulue.
    expect((await row("r-serie"))?.status).toBe("available");
    const status = (await (await call("GET", "/v1/admin/automation", undefined, admin.access_token)).json()) as Record<string, unknown>;
    expect(status).toMatchObject({ plex_synced_at: NOW, plex_status: "ok", plex_items: 2 });

    // Relu au plus toutes les 6 h.
    fetchCalls.length = 0;
    await run(NOW + 3600);
    expect(callsTo("plex.test")).toHaveLength(0);
  });

  it("Plex injoignable : rien ne change et l'état est noté", async () => {
    await saveSettings({ plex: PLEX });
    await request("r-matrix", "movie", 603, "Matrix");
    world({ plex: () => new Response("", { status: 502 }) });
    await run();
    expect((await row("r-matrix"))?.status).toBe("requested");
    const status = (await (await call("GET", "/v1/admin/automation", undefined, admin.access_token)).json()) as Record<string, unknown>;
    expect(status).toMatchObject({ plex_status: "unreachable", plex_items: 0 });
  });
});
