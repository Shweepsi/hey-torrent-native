import { env } from "cloudflare:workers";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { WatchlistItem } from "../src/watchlist";
import { NOW, bootstrapAdmin, call, fetchCalls, replyToFetch, setNow, signInMember, type SessionBody } from "./fakes";

// TMDB est simulé : chaque fiche est servie par `replyToFetch` selon son chemin.

const MATRIX = { id: 603, title: "Matrix", poster_path: "/matrix.jpg", release_date: "1999-03-30" };
const DUNE = { id: 438631, title: "Dune", poster_path: null, release_date: "" };
const SEVERANCE = {
  id: 95396,
  name: "Severance",
  poster_path: "/sev.jpg",
  first_air_date: "2022-02-17",
  seasons: [{ season_number: 0 }, { season_number: 1 }, { season_number: 2 }],
};
const TITLES: Record<string, unknown> = {
  "/3/movie/603": MATRIX,
  "/3/movie/438631": DUNE,
  "/3/tv/95396": SEVERANCE,
};

let admin: SessionBody;
let subjectCounter = 0;

/** Nouveau membre accepté, avec sa session. */
async function member(): Promise<SessionBody> {
  subjectCounter += 1;
  return signInMember("google", { subject: `membre-${subjectCounter}` });
}

beforeAll(async () => {
  admin = await bootstrapAdmin();
});

beforeEach(async () => {
  setNow(NOW);
  fetchCalls.length = 0;
  replyToFetch((url) => {
    const body = TITLES[new URL(url).pathname];
    return body ? new Response(JSON.stringify(body)) : new Response('{"status_code":34}', { status: 404 });
  });
  await env.DB.batch([
    env.DB.prepare("DELETE FROM watchlist_items"),
    env.DB.prepare("DELETE FROM requests"),
    env.DB.prepare("DELETE FROM tmdb_cache"),
  ]);
});

const put = (session: SessionBody, path: string, body: unknown = {}) =>
  call("PUT", `/v1/watchlist/${path}`, body, session.access_token);
const remove = (session: SessionBody, path: string) =>
  call("DELETE", `/v1/watchlist/${path}`, undefined, session.access_token);

async function listOf(session: SessionBody): Promise<WatchlistItem[]> {
  const response = await call("GET", "/v1/watchlist", undefined, session.access_token);
  expect(response.status).toBe(200);
  return ((await response.json()) as { items: WatchlistItem[] }).items;
}

async function errorOf(response: Response): Promise<string> {
  return ((await response.json()) as { error: string }).error;
}

interface RequestRow {
  id: string;
  media_type: string;
  tmdb_id: number;
  title: string;
  status: string;
}
async function requests(): Promise<RequestRow[]> {
  return (
    await env.DB.prepare("SELECT id, media_type, tmdb_id, title, status FROM requests ORDER BY tmdb_id").all<RequestRow>()
  ).results;
}

describe("accès", () => {
  it("exige un jeton d'accès", async () => {
    expect((await call("GET", "/v1/watchlist")).status).toBe(401);
    expect((await call("PUT", "/v1/watchlist/movie/603", {})).status).toBe(401);
    expect((await call("DELETE", "/v1/watchlist/movie/603")).status).toBe(401);
    expect(fetchCalls).toHaveLength(0);
  });

  it("refuse les chemins et méthodes inconnus", async () => {
    expect(await errorOf(await put(admin, "book/1"))).toBe("not_found");
    expect(await errorOf(await put(admin, "movie/0"))).toBe("not_found");
    expect(await errorOf(await put(admin, "movie/12345678901"))).toBe("not_found");
    expect(await errorOf(await put(admin, "movie/603/extra"))).toBe("not_found");
    expect(await errorOf(await call("POST", "/v1/watchlist", {}, admin.access_token))).toBe("not_found");
    expect(await errorOf(await call("GET", "/v1/watchlist/movie/603", undefined, admin.access_token))).toBe("not_found");
  });

  it("ne montre à chacun que sa watchlist", async () => {
    const camille = await member();
    await put(camille, "movie/603");
    expect(await listOf(admin)).toEqual([]);
    expect(await listOf(camille)).toHaveLength(1);
  });
});

describe("ajout", () => {
  it("crée la demande avec le titre, l'affiche et l'année lus chez TMDB", async () => {
    const response = await put(admin, "movie/603", { title: "Titre inventé par l'app" });
    expect(response.status).toBe(200);
    const { item } = (await response.json()) as { item: WatchlistItem };
    expect(item).toEqual({
      media_type: "movie",
      tmdb_id: 603,
      title: "Matrix",
      poster_path: "/matrix.jpg",
      year: 1999,
      seasons: null,
      added_at: NOW,
      status: "requested",
      refusal_reason: null,
    });
    expect(await requests()).toMatchObject([{ media_type: "movie", tmdb_id: 603, title: "Matrix", status: "requested" }]);
  });

  it("rattache une deuxième personne à la même demande", async () => {
    const camille = await member();
    await put(admin, "movie/603");
    setNow(NOW + 60);
    await put(camille, "movie/603");
    expect(await requests()).toHaveLength(1);
    const followers = await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM watchlist_items WHERE request_id = ? AND deleted_at IS NULL",
    )
      .bind((await requests())[0]?.id)
      .first<{ n: number }>();
    expect(followers?.n).toBe(2);
  });

  it("montre l'état de la demande partagée, quel qu'il soit", async () => {
    const camille = await member();
    await put(admin, "movie/603");
    await env.DB.prepare("UPDATE requests SET status = 'in_progress'").run();
    const { item } = (await (await put(camille, "movie/603")).json()) as { item: WatchlistItem };
    expect(item.status).toBe("in_progress");
  });

  it("est idempotent et garde la date du premier ajout", async () => {
    await put(admin, "movie/603");
    // Moins de 15 minutes : le jeton d'accès vaut encore.
    setNow(NOW + 600);
    expect((await put(admin, "movie/603")).status).toBe(200);
    const items = await listOf(admin);
    expect(items).toHaveLength(1);
    expect(items[0]?.added_at).toBe(NOW);
    expect(await requests()).toHaveLength(1);
  });

  it("lit la fiche TMDB dans le cache au deuxième ajout", async () => {
    const camille = await member();
    await put(admin, "movie/603");
    await put(camille, "movie/603");
    expect(fetchCalls).toHaveLength(1);
  });

  it("garde un titre sans affiche ni date", async () => {
    const { item } = (await (await put(admin, "movie/438631")).json()) as { item: WatchlistItem };
    expect(item).toMatchObject({ title: "Dune", poster_path: null, year: null });
  });

  it("refuse un titre inconnu de TMDB, sans rien créer", async () => {
    expect(await errorOf(await put(admin, "movie/999"))).toBe("not_found");
    expect(await requests()).toEqual([]);
  });

  it("refuse une fiche TMDB sans titre", async () => {
    replyToFetch(() => new Response('{"id":5}'));
    expect(await errorOf(await put(admin, "movie/5"))).toBe("not_found");
  });

  it("signale TMDB indisponible, sans rien créer", async () => {
    replyToFetch(() => new Response("panne", { status: 503 }));
    expect(await errorOf(await put(admin, "movie/603"))).toBe("provider_unavailable");
    expect(await requests()).toEqual([]);
  });
});

describe("séries", () => {
  it("demande la série entière par défaut", async () => {
    const { item } = (await (await put(admin, "tv/95396")).json()) as { item: WatchlistItem };
    expect(item).toMatchObject({ media_type: "tv", title: "Severance", year: 2022, seasons: null });
  });

  it("garde les saisons choisies, triées et sans doublon", async () => {
    const { item } = (await (await put(admin, "tv/95396", { seasons: [2, 1, 2] })).json()) as { item: WatchlistItem };
    expect(item.seasons).toEqual([1, 2]);
  });

  it("change les saisons d'une série déjà demandée", async () => {
    await put(admin, "tv/95396", { seasons: [1] });
    await put(admin, "tv/95396", { seasons: [2] });
    expect((await listOf(admin))[0]?.seasons).toEqual([2]);
    await put(admin, "tv/95396", { seasons: null });
    expect((await listOf(admin))[0]?.seasons).toBeNull();
  });

  it("refuse des saisons invalides", async () => {
    for (const seasons of [[], [3], [-1], [1.5], ["1"], "1", [100]]) {
      expect(await errorOf(await put(admin, "tv/95396", { seasons }))).toBe("invalid_request");
    }
    expect(await errorOf(await put(admin, "movie/603", { seasons: [1] }))).toBe("invalid_request");
    expect(await requests()).toEqual([]);
  });
});

describe("retrait", () => {
  it("retire le titre et sa demande que personne d'autre n'attend", async () => {
    await put(admin, "movie/603");
    expect((await remove(admin, "movie/603")).status).toBe(204);
    expect(await listOf(admin)).toEqual([]);
    expect(await requests()).toEqual([]);
  });

  it("garde la demande quand quelqu'un d'autre l'attend", async () => {
    const camille = await member();
    await put(admin, "movie/603");
    await put(camille, "movie/603");
    await remove(admin, "movie/603");
    expect(await listOf(admin)).toEqual([]);
    expect(await listOf(camille)).toHaveLength(1);
    expect(await requests()).toHaveLength(1);
  });

  it("garde une demande en cours ou disponible, même sans personne", async () => {
    await put(admin, "movie/603");
    await put(admin, "tv/95396");
    await env.DB.prepare("UPDATE requests SET status = 'in_progress' WHERE tmdb_id = 603").run();
    await env.DB.prepare("UPDATE requests SET status = 'available' WHERE tmdb_id = 95396").run();
    await remove(admin, "movie/603");
    await remove(admin, "tv/95396");
    expect((await requests()).map((r) => r.status)).toEqual(["in_progress", "available"]);
  });

  it("efface une demande refusée que personne n'attend plus", async () => {
    await put(admin, "movie/603");
    await env.DB.prepare("UPDATE requests SET status = 'refused', refusal_reason = 'Pas trouvé'").run();
    expect((await listOf(admin))[0]).toMatchObject({ status: "refused", refusal_reason: "Pas trouvé" });
    await remove(admin, "movie/603");
    expect(await requests()).toEqual([]);
  });

  it("est sans effet sur un titre absent", async () => {
    expect((await remove(admin, "movie/603")).status).toBe(204);
    await put(admin, "movie/603");
    await remove(admin, "movie/603");
    expect((await remove(admin, "movie/603")).status).toBe(204);
  });

  it("remet le titre à la date d'un nouvel ajout", async () => {
    await put(admin, "movie/603");
    await remove(admin, "movie/603");
    setNow(NOW + 600);
    await put(admin, "movie/603");
    expect(await listOf(admin)).toMatchObject([{ tmdb_id: 603, added_at: NOW + 600, status: "requested" }]);
  });

  it("garde la ligne retirée (suppression logique)", async () => {
    await put(admin, "movie/603");
    setNow(NOW + 5);
    await remove(admin, "movie/603");
    const row = await env.DB.prepare("SELECT deleted_at, updated_at FROM watchlist_items").first();
    expect(row).toEqual({ deleted_at: NOW + 5, updated_at: NOW + 5 });
  });
});

describe("liste", () => {
  it("rend la watchlist la plus récente d'abord", async () => {
    await put(admin, "movie/603");
    setNow(NOW + 10);
    await put(admin, "tv/95396", { seasons: [1] });
    setNow(NOW + 20);
    await put(admin, "movie/438631");
    expect((await listOf(admin)).map((i) => i.tmdb_id)).toEqual([438631, 95396, 603]);
  });
});

describe("suppression du compte", () => {
  it("efface les demandes que plus personne n'attend, garde les autres", async () => {
    const camille = await member();
    const dominique = await member();
    await put(camille, "movie/603");
    await put(camille, "tv/95396");
    await put(dominique, "tv/95396");
    expect((await call("DELETE", "/v1/me", {}, camille.access_token)).status).toBe(204);
    expect((await requests()).map((r) => r.tmdb_id)).toEqual([95396]);
    expect(await listOf(dominique)).toHaveLength(1);
  });
});
