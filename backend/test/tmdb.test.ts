import { env } from "cloudflare:workers";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { PURGE_BATCH } from "../src/tmdb";
import { NOW, bootstrapAdmin, call, fetchCalls, replyToFetch, setNow, signIn, type SessionBody } from "./fakes";

// TMDB est simulé : `deps.fetch` répond avec ce que chaque test décide.

const HOUR = 3600;
const DAY = 24 * HOUR;
const BODY = '{"page":1,"results":[{"id":603,"title":"Matrix"}],"total_pages":1}';
const KEY = env.TMDB_API_KEY ?? "";
/** Faux jeton v4 : seul le préfixe `eyJ` compte pour le Worker. */
const TOKEN_V4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJmYWtlIn0.ZmF1eC1zaWduYXR1cmU";

function withKey(key: string): Cloudflare.Env {
  return { ...env, TMDB_API_KEY: key };
}
const { TMDB_API_KEY: _removed, ...envWithoutKey } = env;

let admin: SessionBody;

beforeAll(async () => {
  admin = await bootstrapAdmin();
});

beforeEach(async () => {
  setNow(NOW);
  fetchCalls.length = 0;
  replyToFetch(() => new Response(BODY, { status: 200 }));
  await env.DB.prepare("DELETE FROM tmdb_cache").run();
});

afterEach(() => {
  vi.restoreAllMocks();
});

const relay = (path: string, workerEnv?: Cloudflare.Env) =>
  call("GET", `/v1/tmdb/${path}`, undefined, admin.access_token, workerEnv);

/** Comme `relay`, mais d'un cache vide : la réponse vient forcément d'un nouvel appel à TMDB. */
async function relayFresh(path: string): Promise<Response> {
  await env.DB.prepare("DELETE FROM tmdb_cache").run();
  return relay(path);
}

/** Appel à l'heure `time`, avec un jeton d'accès valable alors (ils durent 15 minutes). */
async function relayAt(time: number, path: string): Promise<Response> {
  setNow(time);
  const session = (await (await signIn("apple", { subject: "admin-apple" })).json()) as SessionBody;
  return call("GET", `/v1/tmdb/${path}`, undefined, session.access_token);
}

async function errorOf(response: Response): Promise<string> {
  return ((await response.json()) as { error: string }).error;
}

/** Le dernier appel sortant du Worker, décomposé. La clé d'API est mise à part. */
function upstream() {
  const last = fetchCalls.at(-1);
  if (!last) throw new Error("aucun appel sortant");
  const url = new URL(last.url);
  const { api_key: apiKey, ...params } = Object.fromEntries(url.searchParams);
  return { origin: url.origin, path: url.pathname, params, apiKey, headers: last.headers };
}

interface CacheRow {
  cache_key: string;
  body: string;
  expires_at: number;
}
async function cacheRows(): Promise<CacheRow[]> {
  return (await env.DB.prepare("SELECT cache_key, body, expires_at FROM tmdb_cache ORDER BY cache_key").all<CacheRow>())
    .results;
}

describe("accès", () => {
  it("exige un jeton d'accès, avant tout accès au cache ou au réseau", async () => {
    expect((await relay("movie/popular")).status).toBe(200);
    fetchCalls.length = 0;

    // Une entrée fraîche est en cache : sans jeton, elle n'est pas servie.
    const anonymous = await call("GET", "/v1/tmdb/movie/popular");
    expect(anonymous.status).toBe(401);
    expect(await errorOf(anonymous)).toBe("unauthorized");
    const forged = await call("GET", "/v1/tmdb/movie/popular", undefined, "abc.def.ghi");
    expect(await errorOf(forged)).toBe("unauthorized");
    // Même un chemin inconnu : le jeton passe en premier.
    expect(await errorOf(await call("GET", "/v1/tmdb/inconnu"))).toBe("unauthorized");
    expect(fetchCalls).toHaveLength(0);
  });

  it("refuse un jeton expiré", async () => {
    setNow(NOW + 901);
    expect(await errorOf(await call("GET", "/v1/tmdb/movie/popular", undefined, admin.access_token))).toBe(
      "unauthorized",
    );
    expect(fetchCalls).toHaveLength(0);
  });

  it("ne répond qu'à GET", async () => {
    for (const method of ["POST", "PUT", "DELETE", "PATCH"]) {
      const response = await call(method, "/v1/tmdb/movie/popular", undefined, admin.access_token);
      expect(await errorOf(response), method).toBe("not_found");
    }
    expect(fetchCalls).toHaveLength(0);
  });

  it("répond en JSON, sans mise en cache par les clients", async () => {
    for (const _ of [1, 2]) {
      const response = await relay("movie/popular");
      expect(response.headers.get("content-type")).toBe("application/json; charset=utf-8");
      expect(response.headers.get("cache-control")).toBe("no-store");
    }
  });
});

describe("liste blanche", () => {
  const ALLOWED = [
    "trending/all/day",
    "trending/movie/week",
    "trending/tv/day",
    "movie/popular",
    "movie/now_playing",
    "movie/upcoming",
    "movie/top_rated",
    "tv/popular",
    "tv/on_the_air",
    "tv/airing_today",
    "tv/top_rated",
    "genre/movie/list",
    "genre/tv/list",
    "discover/movie",
    "discover/tv",
    "search/multi?query=matrix",
    "movie/603",
    "movie/9999999999",
    "tv/1399",
    "tv/1399/season/0",
    "tv/1399/season/99",
  ];

  it.each(ALLOWED)("relaie %s vers TMDB v3, en HTTPS", async (path) => {
    const response = await relay(path);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(BODY);
    expect(fetchCalls).toHaveLength(1);
    const sent = upstream();
    expect(sent.origin).toBe("https://api.themoviedb.org");
    expect(sent.path).toBe(`/3/${path.split("?")[0]}`);
  });

  it("refuse tout le reste : 404, sans appel sortant", async () => {
    const REFUSED = [
      "",
      "movie",
      "tv",
      "trending",
      "trending/all",
      "trending/person/day",
      "trending/all/month",
      "trending/all/day/extra",
      "movie/latest",
      "movie/popular/extra",
      "movie/popular/",
      "movie/on_the_air",
      "tv/latest",
      "tv/now_playing",
      "tv/changes",
      "genre/person/list",
      "genre/movie",
      "discover/person",
      "discover",
      "search/movie",
      "search/person",
      "search/multi/extra",
      "person/287",
      "collection/10",
      "network/213",
      "account",
      "configuration",
      "authentication",
      "authentication/token/new",
      "movie/603/credits",
      "movie/603/similar",
      "movie/603/",
      "movie/603/season/1",
      "movie/12345678901",
      "movie/-1",
      "movie/1.5",
      "movie/abc",
      "movie/603abc",
      "tv/1399/season",
      "tv/1399/season/",
      "tv/1399/season/100",
      "tv/1399/season/-1",
      "tv/1399/season/abc",
      "tv/1399/season/1/",
      "tv/1399/season/1/episode/1",
      "tv/1399/episode_groups",
      "Movie/popular",
      "movie/Popular",
      "movie%2Fpopular",
      "movie/%70opular",
      "movie/%2e%2e/account",
      "movie//popular",
      "https://example.com/movie/popular",
    ];
    for (const path of REFUSED) {
      const response = await relay(path);
      expect(response.status, path).toBe(404);
      expect(await errorOf(response), path).toBe("not_found");
    }
    for (const path of ["/v1/tmdb", "/v1/tmdbx/movie/popular"]) {
      expect(await errorOf(await call("GET", path, undefined, admin.access_token)), path).toBe("not_found");
    }
    expect(fetchCalls).toHaveLength(0);
  });

  it("normalise les identifiants (zéros de tête)", async () => {
    await relay("movie/0000000603");
    expect(upstream().path).toBe("/3/movie/603");
    await relay("tv/001399/season/03");
    expect(upstream().path).toBe("/3/tv/1399/season/3");
  });
});

describe("paramètres", () => {
  const FIXED_JUNK =
    "language=en-US&include_adult=true&region=US&watch_region=US&include_video_language=de" +
    "&append_to_response=account_states&api_key=evil&session_id=s&foo=bar";
  const DETAIL = {
    append_to_response: "credits,videos,watch/providers,similar",
    include_video_language: "fr,en,null",
    language: "fr-FR",
  };

  const CASES: [string, string, Record<string, string>][] = [
    ["trending/all/week?page=2", "trending/all/week", { include_adult: "false", language: "fr-FR", page: "2" }],
    [
      "movie/now_playing",
      "movie/now_playing",
      { include_adult: "false", language: "fr-FR", page: "1", region: "FR" },
    ],
    [
      "tv/on_the_air?page=500",
      "tv/on_the_air",
      { include_adult: "false", language: "fr-FR", page: "500", region: "FR" },
    ],
    ["genre/tv/list", "genre/tv/list", { language: "fr-FR" }],
    [
      "discover/movie?with_genres=16%7C28&sort_by=vote_average.desc&page=3",
      "discover/movie",
      {
        include_adult: "false",
        language: "fr-FR",
        page: "3",
        sort_by: "vote_average.desc",
        watch_region: "FR",
        with_genres: "16|28",
      },
    ],
    ["discover/tv", "discover/tv", { include_adult: "false", language: "fr-FR", page: "1", watch_region: "FR" }],
    [
      "search/multi?query=matrix&page=4",
      "search/multi",
      { include_adult: "false", language: "fr-FR", page: "4", query: "matrix" },
    ],
    ["movie/603", "movie/603", DETAIL],
    ["tv/1399", "tv/1399", DETAIL],
    ["tv/1399/season/2", "tv/1399/season/2", { language: "fr-FR" }],
  ];

  it.each(CASES)("%s : réglages imposés, paramètres parasites non transmis", async (path, upstreamPath, params) => {
    const separator = path.includes("?") ? "&" : "?";
    expect((await relay(`${path}${separator}${FIXED_JUNK}`)).status).toBe(200);
    const sent = upstream();
    expect(sent.path).toBe(`/3/${upstreamPath}`);
    // Exactement les paramètres attendus : rien du client, hors liste blanche.
    expect(sent.params).toEqual(params);
    expect(sent.apiKey).toBe(KEY);
  });

  it("n'examine pas les paramètres que la route n'accepte pas", async () => {
    expect((await relay("movie/popular?with_genres=abc&sort_by=nope&query=")).status).toBe(200);
    expect(upstream().params).toEqual({ include_adult: "false", language: "fr-FR", page: "1", region: "FR" });
    expect((await relay("movie/603?page=999&with_genres=abc")).status).toBe(200);
    expect(upstream().params).toEqual(DETAIL);
  });

  describe("page", () => {
    const PAGED = [
      "trending/all/day",
      "trending/tv/week",
      "movie/popular",
      "movie/now_playing",
      "movie/upcoming",
      "movie/top_rated",
      "tv/popular",
      "tv/on_the_air",
      "tv/airing_today",
      "tv/top_rated",
      "discover/movie",
      "discover/tv",
      "search/multi?query=matrix",
    ];

    it("accepte 1 à 500", async () => {
      for (const path of PAGED) {
        for (const page of ["1", "42", "500"]) {
          const response = await relayFresh(`${path}${path.includes("?") ? "&" : "?"}page=${page}`);
          expect(response.status, `${path} page ${page}`).toBe(200);
          expect(upstream().params.page).toBe(page);
        }
      }
    });

    it("refuse ce qui n'est pas un entier de 1 à 500", async () => {
      const INVALID = ["0", "501", "5000", "-1", "abc", "1.5", "", "1e2", "%2B3", "%203", "3%20", "0x10"];
      for (const path of PAGED) {
        for (const page of INVALID) {
          const response = await relay(`${path}${path.includes("?") ? "&" : "?"}page=${page}`);
          expect(response.status, `${path} page=${page}`).toBe(400);
          expect(await errorOf(response)).toBe("invalid_request");
        }
      }
      expect(fetchCalls).toHaveLength(0);
    });

    it("refuse un paramètre répété", async () => {
      expect(await errorOf(await relay("movie/popular?page=1&page=2"))).toBe("invalid_request");
      expect(fetchCalls).toHaveLength(0);
    });
  });

  describe("with_genres et sort_by (discover)", () => {
    it("accepte des identifiants séparés par , ou |, triés et sans doublon", async () => {
      for (const [given, sent] of [
        ["28", "28"],
        ["28,12", "12,28"],
        ["12%2C28%2C12", "12,28"],
        ["28%7C12", "12|28"],
        ["0016,28", "16,28"],
        ["1,2,3,4,5,6,7,8,9,10", "1,2,3,4,5,6,7,8,9,10"],
      ] as const) {
        const response = await relayFresh(`discover/tv?with_genres=${given}`);
        expect(response.status, given).toBe(200);
        expect(upstream().params.with_genres).toBe(sent);
      }
    });

    it("refuse le reste", async () => {
      const INVALID = [
        "",
        "abc",
        "28,",
        ",28",
        "28,,12",
        "28;12",
        "28,12%7C16",
        "28%20",
        "-28",
        "28.5",
        "123456789",
        "1,2,3,4,5,6,7,8,9,10,11",
      ];
      for (const genres of INVALID) {
        const response = await relay(`discover/movie?with_genres=${genres}`);
        expect(response.status, genres).toBe(400);
        expect(await errorOf(response)).toBe("invalid_request");
      }
      expect(fetchCalls).toHaveLength(0);
    });

    it("n'accepte que les quatre tris de la liste", async () => {
      for (const sort of ["popularity.desc", "vote_average.desc", "primary_release_date.desc", "first_air_date.desc"]) {
        expect((await relayFresh(`discover/movie?sort_by=${sort}`)).status, sort).toBe(200);
        expect(upstream().params.sort_by).toBe(sort);
      }
      for (const sort of ["", "title.asc", "popularity.asc", "popularity", "POPULARITY.DESC", "revenue.desc"]) {
        expect(await errorOf(await relay(`discover/tv?sort_by=${sort}`)), sort).toBe("invalid_request");
      }
      expect(fetchCalls).toHaveLength(4);
    });
  });

  describe("query (search/multi)", () => {
    it("est obligatoire, de 1 à 100 caractères", async () => {
      for (const query of ["", "%20%20%09", "a".repeat(101), "%F0%9F%98%80".repeat(101)]) {
        const response = await relay(`search/multi?query=${query}`);
        expect(response.status, query).toBe(400);
        expect(await errorOf(response)).toBe("invalid_request");
      }
      expect(await errorOf(await relay("search/multi"))).toBe("invalid_request");
      expect(await errorOf(await relay("search/multi?page=2"))).toBe("invalid_request");
      expect(await errorOf(await relay("search/multi?query=a&query=b"))).toBe("invalid_request");
      expect(fetchCalls).toHaveLength(0);

      for (const query of ["a", "a".repeat(100), "%F0%9F%98%80".repeat(100)]) {
        expect((await relay(`search/multi?query=${query}`)).status, query).toBe(200);
      }
    });

    it("transmet le texte tel quel, espaces de bord retirés, correctement encodé", async () => {
      await relay("search/multi?query=%20Am%C3%A9lie%20%26%20Co%20%3D%20%3F%23%2B%20");
      expect(upstream().params.query).toBe("Amélie & Co = ?#+");
    });
  });
});

describe("clé TMDB", () => {
  it("clé v3 : paramètre api_key, pas d'en-tête d'autorisation", async () => {
    await relay("movie/popular");
    const sent = upstream();
    expect(sent.apiKey).toBe(KEY);
    expect(sent.headers.authorization).toBeUndefined();
    expect(sent.headers.accept).toBe("application/json");
  });

  it("jeton v4 (eyJ…) : en-tête Bearer, pas de paramètre api_key", async () => {
    expect((await relay("movie/popular", withKey(TOKEN_V4))).status).toBe(200);
    const sent = upstream();
    expect(sent.headers.authorization).toBe(`Bearer ${TOKEN_V4}`);
    expect(sent.apiKey).toBeUndefined();
    expect(new URL(fetchCalls[0]?.url ?? "").search).not.toContain(TOKEN_V4);
  });

  it("ignore les espaces et sauts de ligne autour du secret", async () => {
    await relay("movie/popular", withKey(`  ${KEY}\n`));
    expect(upstream().apiKey).toBe(KEY);
    await relay("tv/popular", withKey(`${TOKEN_V4}\r\n`));
    expect(upstream().headers.authorization).toBe(`Bearer ${TOKEN_V4}`);
  });

  it("clé absente ou vide : 503 provider_unavailable, sans appel sortant", async () => {
    for (const workerEnv of [envWithoutKey, withKey(""), withKey("  \n")]) {
      const response = await relay("movie/popular", workerEnv);
      expect(response.status).toBe(503);
      expect(await errorOf(response)).toBe("provider_unavailable");
    }
    expect(fetchCalls).toHaveLength(0);
  });

  it("sert une entrée du cache même sans clé, mais pas une absente", async () => {
    await relay("movie/popular");
    expect((await relay("movie/popular", envWithoutKey)).status).toBe(200);
    expect(await errorOf(await relay("tv/popular", envWithoutKey))).toBe("provider_unavailable");
    expect(fetchCalls).toHaveLength(1);
  });

  it("n'apparaît jamais dans le cache, les réponses ni les journaux", async () => {
    const logged: string[] = [];
    for (const method of ["log", "info", "warn", "error", "debug"] as const) {
      vi.spyOn(console, method).mockImplementation((...args: unknown[]) => {
        logged.push(args.map(String).join(" "));
      });
    }
    const texts: string[] = [];
    for (const [workerEnv, secret] of [
      [env, KEY],
      [withKey(TOKEN_V4), TOKEN_V4],
    ] as const) {
      await env.DB.prepare("DELETE FROM tmdb_cache").run();
      // Réussite (mise en cache), puis échecs de toute nature.
      texts.push(await (await relay("tv/1399", workerEnv)).text());
      texts.push(await (await relay("tv/1399", workerEnv)).text());
      replyToFetch(() => new Response("{}", { status: 500 }));
      texts.push(await (await relay("movie/popular", workerEnv)).text());
      replyToFetch(() => Promise.reject(new TypeError(`échec réseau pour ${secret}`)));
      texts.push(await (await relay("movie/upcoming", workerEnv)).text());
      replyToFetch(() => new Response("pas du JSON", { status: 200 }));
      texts.push(await (await relay("movie/top_rated", workerEnv)).text());
      replyToFetch(() => new Response(BODY, { status: 200 }));

      for (const row of await cacheRows()) {
        expect(row.cache_key).not.toContain(secret);
        expect(row.body).not.toContain(secret);
      }
      for (const text of texts) expect(text).not.toContain(secret);
      expect(logged.join("\n")).not.toContain(secret);
    }
    expect(logged.join("\n")).not.toContain("api_key");
    // Les échecs ont bien été journalisés : le contrôle ci-dessus n'est pas vide.
    expect(logged.length).toBeGreaterThan(0);
  });
});

describe("erreurs de TMDB", () => {
  it("404 de TMDB : 404 not_found, sans mise en cache", async () => {
    replyToFetch(() => new Response('{"success":false,"status_code":34}', { status: 404 }));
    const response = await relay("movie/999999999");
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not_found" });
    expect(await cacheRows()).toHaveLength(0);
  });

  it.each([401, 403, 422, 429, 500, 502, 503, 504])("%i de TMDB : 503 provider_unavailable", async (status) => {
    replyToFetch(() => new Response('{"status_message":"refusé"}', { status }));
    const response = await relay("tv/1399");
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ error: "provider_unavailable" });
    expect(await cacheRows()).toHaveLength(0);
  });

  it("panne réseau : 503 provider_unavailable", async () => {
    replyToFetch(() => Promise.reject(new TypeError("fetch failed")));
    expect(await errorOf(await relay("movie/popular"))).toBe("provider_unavailable");
    expect(await cacheRows()).toHaveLength(0);
  });

  it("délai dépassé : 503 provider_unavailable, avec un délai armé sur l'appel", async () => {
    replyToFetch(() => Promise.reject(new DOMException("The operation timed out.", "TimeoutError")));
    const response = await relay("movie/popular");
    expect(response.status).toBe(503);
    expect(await errorOf(response)).toBe("provider_unavailable");
    const signal = fetchCalls[0]?.signal;
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(signal?.aborted).toBe(false);
  });

  it("corps illisible : 503 provider_unavailable, jamais mis en cache", async () => {
    for (const body of ["", "<html>Bad gateway</html>", '{"page":', "[]", "null", '"texte"', "42"]) {
      replyToFetch(() => new Response(body, { status: 200 }));
      const response = await relay("movie/popular");
      expect(response.status, body).toBe(503);
      expect(await errorOf(response)).toBe("provider_unavailable");
    }
    expect(await cacheRows()).toHaveLength(0);
  });

  it("une erreur n'empêche pas la reprise : l'appel suivant interroge de nouveau TMDB", async () => {
    replyToFetch(() => new Response("{}", { status: 500 }));
    expect((await relay("movie/popular")).status).toBe(503);
    replyToFetch(() => new Response(BODY, { status: 200 }));
    const response = await relay("movie/popular");
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(BODY);
    expect(fetchCalls).toHaveLength(2);
  });
});

describe("cache D1", () => {
  it("sert le deuxième appel sans interroger TMDB", async () => {
    const first = await relay("movie/popular");
    replyToFetch(() => new Response('{"autre":true}', { status: 200 }));
    const second = await relay("movie/popular");
    expect(await first.text()).toBe(BODY);
    expect(await second.text()).toBe(BODY);
    expect(fetchCalls).toHaveLength(1);
  });

  it("garde le corps de TMDB tel quel, sans le réécrire", async () => {
    const raw = '{ "id": 12345678901234567890,\n  "note": 1.0, "titre": "Léon \\u00e9té" }';
    replyToFetch(() => new Response(raw, { status: 200 }));
    expect(await (await relay("tv/1399")).text()).toBe(raw);
    expect(await (await relay("tv/1399")).text()).toBe(raw);
    expect((await cacheRows())[0]?.body).toBe(raw);
    expect(fetchCalls).toHaveLength(1);
  });

  it("clé = chemin et paramètres triés, sans clé d'API", async () => {
    await relay("movie/popular?page=2&foo=bar");
    expect((await cacheRows()).map((row) => row.cache_key)).toEqual([
      "movie/popular?include_adult=false&language=fr-FR&page=2&region=FR",
    ]);
  });

  it("partage l'entrée entre les écritures équivalentes d'une même requête", async () => {
    const EQUIVALENT = [
      "movie/popular",
      "movie/popular?page=1",
      "movie/popular?page=001&foo=bar&language=en-US&api_key=x",
      "movie/popular?foo=baz",
    ];
    for (const path of EQUIVALENT) expect((await relay(path)).status, path).toBe(200);
    expect(fetchCalls).toHaveLength(1);
    expect(await cacheRows()).toHaveLength(1);

    for (const given of ["28,12", "12%2C28", "12,28,12", "0028,12"]) {
      expect((await relay(`discover/movie?with_genres=${given}`)).status, given).toBe(200);
    }
    expect(fetchCalls).toHaveLength(2);

    expect((await relay("movie/0603")).status).toBe(200);
    expect((await relay("movie/603")).status).toBe(200);
    expect(fetchCalls).toHaveLength(3);
    expect(await cacheRows()).toHaveLength(3);
  });

  it("sépare ce qui change la réponse", async () => {
    for (const path of [
      "movie/popular?page=1",
      "movie/popular?page=2",
      "tv/popular?page=1",
      "discover/movie?with_genres=28",
      "discover/movie?with_genres=28&sort_by=vote_average.desc",
      "discover/movie?with_genres=28%7C12",
      "discover/movie?with_genres=28%2C12",
    ]) {
      await relay(path);
    }
    expect(fetchCalls).toHaveLength(7);
    expect(await cacheRows()).toHaveLength(7);
  });

  it("ne dépend pas de la forme de la clé TMDB", async () => {
    await relay("movie/popular");
    expect((await relay("movie/popular", withKey(TOKEN_V4))).status).toBe(200);
    expect(fetchCalls).toHaveLength(1);
  });

  it("ne met jamais la recherche en cache", async () => {
    await relay("search/multi?query=matrix");
    await relay("search/multi?query=matrix");
    expect(fetchCalls).toHaveLength(2);
    expect(await cacheRows()).toHaveLength(0);
  });

  const TTLS: [string, number][] = [
    ["trending/all/day", HOUR],
    ["trending/tv/week", HOUR],
    ["movie/popular", 6 * HOUR],
    ["movie/now_playing", 6 * HOUR],
    ["movie/upcoming", 6 * HOUR],
    ["movie/top_rated", 6 * HOUR],
    ["tv/popular", 6 * HOUR],
    ["tv/on_the_air", 6 * HOUR],
    ["tv/airing_today", 6 * HOUR],
    ["tv/top_rated", 6 * HOUR],
    ["genre/movie/list", 7 * DAY],
    ["genre/tv/list", 7 * DAY],
    ["discover/movie?with_genres=28", 6 * HOUR],
    ["discover/tv", 6 * HOUR],
    ["movie/603", DAY],
    ["tv/1399", DAY],
    ["tv/1399/season/1", 12 * HOUR],
  ];

  it.each(TTLS)("%s : valable %i s d'après deps.now()", async (path, ttl) => {
    expect((await relay(path)).status).toBe(200);
    expect((await cacheRows())[0]?.expires_at).toBe(NOW + ttl);

    // Une seconde avant l'échéance : toujours servie par le cache.
    expect((await relayAt(NOW + ttl - 1, path)).status).toBe(200);
    expect(fetchCalls).toHaveLength(1);

    // À l'échéance : expirée, TMDB est interrogé de nouveau et l'entrée renouvelée.
    replyToFetch(() => new Response('{"renouvelé":true}', { status: 200 }));
    const renewed = await relayAt(NOW + ttl, path);
    expect(await renewed.text()).toBe('{"renouvelé":true}');
    expect(fetchCalls).toHaveLength(2);
    const rows = await cacheRows();
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ body: '{"renouvelé":true}', expires_at: NOW + ttl + ttl });
  });

  it("interroge de nouveau TMDB pour une entrée expirée et la remplace", async () => {
    await relay("tv/1399");
    await env.DB.prepare("UPDATE tmdb_cache SET body = ?, expires_at = ?").bind('{"périmé":true}', NOW - 1).run();
    expect(await (await relay("tv/1399")).text()).toBe(BODY);
    expect(fetchCalls).toHaveLength(2);
    expect(await cacheRows()).toEqual([expect.objectContaining({ body: BODY, expires_at: NOW + DAY })]);
  });

  it("sert une entrée fraîche écrite auparavant", async () => {
    await relay("genre/movie/list");
    await env.DB.prepare("UPDATE tmdb_cache SET body = ?").bind('{"genres":[]}').run();
    expect(await (await relay("genre/movie/list")).text()).toBe('{"genres":[]}');
    expect(fetchCalls).toHaveLength(1);
  });

  it("efface quelques entrées expirées à chaque écriture, pas les autres", async () => {
    const insert = env.DB.prepare("INSERT INTO tmdb_cache (cache_key, body, expires_at) VALUES (?, '{}', ?)");
    const expired = PURGE_BATCH + 5;
    await env.DB.batch([
      ...Array.from({ length: expired }, (_, i) => insert.bind(`expirée-${i}`, NOW - 100 - i)),
      insert.bind("échéance-exacte", NOW),
      insert.bind("fraîche", NOW + 1000),
    ]);

    await relay("movie/popular");

    const keys = (await cacheRows()).map((row) => row.cache_key);
    expect(keys).toContain("fraîche");
    expect(keys).toContain("movie/popular?include_adult=false&language=fr-FR&page=1&region=FR");
    const stillExpired = keys.filter((key) => key.startsWith("expirée-") || key === "échéance-exacte");
    // Une purge bornée : PURGE_BATCH entrées, pas toute la table.
    expect(stillExpired).toHaveLength(expired + 1 - PURGE_BATCH);
  });

  describe("panne de D1", () => {
    const readFails = {
      prepare() {
        throw new Error("D1 hors service");
      },
      batch: () => Promise.reject(new Error("D1 hors service")),
    } as unknown as D1Database;
    const writeFails = {
      prepare: (sql: string) => env.DB.prepare(sql),
      batch: () => Promise.reject(new Error("quota D1 dépassé")),
    } as unknown as D1Database;

    it("n'est pas fatale à la lecture : la réponse de TMDB est servie", async () => {
      const response = await relay("movie/popular", { ...env, DB: readFails });
      expect(response.status).toBe(200);
      expect(await response.text()).toBe(BODY);
      await relay("movie/popular", { ...env, DB: readFails });
      expect(fetchCalls).toHaveLength(2);
    });

    it("n'est pas fatale à l'écriture : la réponse de TMDB est servie", async () => {
      const response = await relay("tv/1399", { ...env, DB: writeFails });
      expect(response.status).toBe(200);
      expect(await response.text()).toBe(BODY);
      expect(await cacheRows()).toHaveLength(0);
    });

    it("n'empêche pas les erreurs de TMDB d'être signalées", async () => {
      replyToFetch(() => new Response("{}", { status: 429 }));
      expect(await errorOf(await relay("movie/popular", { ...env, DB: readFails }))).toBe("provider_unavailable");
    });
  });
});
