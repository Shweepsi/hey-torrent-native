import { env } from "cloudflare:workers";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { MAX_CANDIDATES, RECOMMEND_MODEL } from "../src/claude";
import { bootstrapAdmin, call, fetchCalls, replyToFetch, signInMember, type SessionBody } from "./fakes";

// L'API Anthropic est simulée : aucune vraie clé, aucun appel réseau.

let admin: SessionBody;
let member: SessionBody;

beforeAll(async () => {
  admin = await bootstrapAdmin();
  member = await signInMember("google", { subject: "membre-reco", givenName: "Lou" });
});

const RELEASES = [
  { id: "r1", title: "Film.2024.MULTi.VFF.2160p.WEB.x265-GRP", size_bytes: 12_000_000_000, seeders: 40, indexer: "Source A" },
  { id: "r2", title: "Film.2024.VOSTFR.1080p.WEB.x264-AUTRE", size_bytes: 3_000_000_000, seeders: 120 },
  { pas: "lisible" },
];

const QUERY = { media_type: "movie", title: "Le Film", year: 2024, releases: RELEASES };

async function saveSettings(settings: Record<string, unknown>) {
  const response = await call("PUT", "/v1/admin/settings", { settings: JSON.stringify(settings) }, admin.access_token);
  expect(response.status).toBe(200);
}

function claudeReply(text: string, stopReason = "end_turn"): Response {
  return Response.json({
    id: "msg_test",
    type: "message",
    role: "assistant",
    model: RECOMMEND_MODEL,
    content: [{ type: "text", text }],
    stop_reason: stopReason,
    stop_sequence: null,
    usage: { input_tokens: 500, output_tokens: 40 },
  });
}

const recommend = (body: unknown = QUERY, token = admin.access_token) => call("POST", "/v1/admin/recommend", body, token);
const errorOf = async (response: Response) => ((await response.json()) as { error: string }).error;

beforeEach(async () => {
  fetchCalls.length = 0;
  await env.DB.prepare("DELETE FROM credentials").run();
});

describe("recommandation", () => {
  it("rend la release choisie et sa raison, avec la clé et les règles de l'administrateur", async () => {
    await saveSettings({ anthropic_api_key: "cle-de-test", release_rules: "MULTi VFF, 15 Go maximum" });
    replyToFetch(() => claudeReply(JSON.stringify({ choice: "r1", reason: "MULTi VFF en 4K, sous 15 Go." })));

    const response = await recommend();
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ release_id: "r1", reason: "MULTi VFF en 4K, sous 15 Go." });

    expect(fetchCalls).toHaveLength(1);
    const sent = fetchCalls[0]!;
    expect(sent.url).toBe("https://api.anthropic.com/v1/messages");
    expect(sent.headers["anthropic-beta"]).toBe("server-side-fallback-2026-07-01");
    expect(sent.headers.authorization).toBe("Bearer cle-de-test");
    const body = JSON.parse(sent.body) as {
      model: string;
      fallbacks: string;
      output_config: { effort: string; format: { schema: { properties: { choice: { enum: string[] } } } } };
      messages: { content: string }[];
    };
    expect(body.model).toBe(RECOMMEND_MODEL);
    expect(body.fallbacks).toBe("default");
    expect(body.output_config.effort).toBe("low");
    // Seules les releases lisibles sont proposées, plus « aucune ».
    expect(body.output_config.format.schema.properties.choice.enum).toEqual(["r1", "r2", "none"]);
    const prompt = JSON.parse(body.messages[0]!.content) as { regles: string; releases: { nom: string; taille?: string }[] };
    expect(prompt.regles).toBe("MULTi VFF, 15 Go maximum");
    expect(prompt.releases[0]).toMatchObject({ nom: RELEASES[0]!.title, taille: "12.0 Go" });
  });

  it("« none » : aucune release ne convient", async () => {
    await saveSettings({ anthropic_api_key: "cle-de-test" });
    replyToFetch(() => claudeReply(JSON.stringify({ choice: "none", reason: "Aucune version française." })));
    expect(await (await recommend()).json()).toEqual({ release_id: null, reason: "Aucune version française." });
  });

  it("sans release, Claude n'est pas appelé", async () => {
    await saveSettings({ anthropic_api_key: "cle-de-test" });
    const response = await recommend({ ...QUERY, releases: [] });
    expect(await response.json()).toEqual({ release_id: null, reason: "" });
    expect(fetchCalls).toHaveLength(0);
  });

  it("au plus MAX_CANDIDATES releases partent", async () => {
    await saveSettings({ anthropic_api_key: "cle-de-test" });
    replyToFetch(() => claudeReply(JSON.stringify({ choice: "x0", reason: "La première." })));
    const many = Array.from({ length: MAX_CANDIDATES + 10 }, (_, i) => ({ id: `x${i}`, title: `Film.${i}` }));
    expect((await recommend({ ...QUERY, releases: many })).status).toBe(200);
    const body = JSON.parse(fetchCalls[0]!.body) as { output_config: { format: { schema: { properties: { choice: { enum: string[] } } } } } };
    expect(body.output_config.format.schema.properties.choice.enum).toHaveLength(MAX_CANDIDATES + 1);
  });

  it("sans clé : assistant_not_configured", async () => {
    let response = await recommend();
    expect(response.status).toBe(409);
    expect(await errorOf(response)).toBe("assistant_not_configured");
    await saveSettings({ anthropic_api_key: "  " });
    expect(await errorOf(await recommend())).toBe("assistant_not_configured");
    expect(fetchCalls).toHaveLength(0);
  });

  it("clé refusée : assistant_auth_failed", async () => {
    await saveSettings({ anthropic_api_key: "cle-perimee" });
    replyToFetch(() =>
      Response.json({ type: "error", error: { type: "authentication_error", message: "invalid x-api-key" } }, { status: 401 }),
    );
    const response = await recommend();
    expect(response.status).toBe(502);
    expect(await errorOf(response)).toBe("assistant_auth_failed");
  });

  it("panne, réponse illisible, choix inventé ou refus : assistant_unavailable", async () => {
    await saveSettings({ anthropic_api_key: "cle-de-test" });
    for (const reply of [
      () => {
        throw new TypeError("réseau coupé");
      },
      () => claudeReply("pas du json"),
      () => claudeReply(JSON.stringify({ choice: "r9", reason: "Inventée." })),
      () => claudeReply("", "refusal"),
      () => claudeReply(JSON.stringify({ choice: "r1" })),
    ]) {
      replyToFetch(reply);
      const response = await recommend();
      expect(response.status).toBe(503);
      expect(await errorOf(response)).toBe("assistant_unavailable");
    }
  });

  it("refuse un corps invalide et les membres", async () => {
    await saveSettings({ anthropic_api_key: "cle-de-test" });
    expect((await recommend({ ...QUERY, media_type: "music" })).status).toBe(400);
    expect((await recommend({ ...QUERY, releases: "r1" })).status).toBe(400);
    expect((await recommend({ ...QUERY, year: -1 })).status).toBe(400);
    expect((await recommend(QUERY, member.access_token)).status).toBe(403);
    expect(fetchCalls).toHaveLength(0);
  });
});
