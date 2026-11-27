// Tests de play-upload.mjs : Google est simulé (fetch injecté), jamais appelé.
import assert from "node:assert/strict";
import { generateKeyPairSync, createVerify } from "node:crypto";
import { test } from "node:test";
import { PlayError, readServiceAccount, releaseNotes, signedAssertion, uploadToPlay } from "./play-upload.mjs";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const KEY = JSON.stringify({
  type: "service_account",
  client_email: "ci@projet-factice.iam.gserviceaccount.com",
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
  token_uri: "https://oauth2.example.test/token",
});

const reply = (status, body) => new Response(body === undefined ? null : JSON.stringify(body), { status });

/** Faux Google : répond selon la méthode et le chemin, garde les appels. */
function fakeGoogle(overrides = {}) {
  const calls = [];
  const fetchFn = async (url, init = {}) => {
    const { pathname } = new URL(url);
    const method = init.method ?? "GET";
    calls.push({ method, pathname, init });
    const key = `${method} ${pathname}`;
    for (const [pattern, handler] of Object.entries(overrides)) {
      if (key.includes(pattern)) {
        const result = handler(calls);
        if (result) return result;
      }
    }
    if (pathname === "/token") return reply(200, { access_token: "jeton-factice" });
    if (key.endsWith("/edits")) return reply(200, { id: `edit-${calls.length}` });
    if (key.includes("/bundles")) return reply(200, { versionCode: 700 });
    if (key.includes("/tracks/")) return reply(200, {});
    if (key.endsWith(":commit")) return reply(200, {});
    return reply(404, { error: { message: "route inconnue" } });
  };
  return { calls, fetchFn };
}

const base = { serviceAccount: KEY, packageName: "io.example.app", aab: Buffer.from("aab"), now: 1_800_000_000 };

test("lit la clé brute ou en base64, refuse le reste", () => {
  assert.equal(readServiceAccount(KEY).email, "ci@projet-factice.iam.gserviceaccount.com");
  assert.equal(readServiceAccount(Buffer.from(KEY).toString("base64")).tokenUri, "https://oauth2.example.test/token");
  assert.throws(() => readServiceAccount(""), PlayError);
  assert.throws(() => readServiceAccount("pas du json"), PlayError);
  assert.throws(() => readServiceAccount('{"type":"authorized_user"}'), PlayError);
});

test("signe l'assertion avec la clé du compte de service", () => {
  const jwt = signedAssertion(readServiceAccount(KEY), 1_800_000_000);
  const [header, claims, signature] = jwt.split(".");
  const verifier = createVerify("RSA-SHA256");
  verifier.update(`${header}.${claims}`);
  assert.ok(verifier.verify(publicKey, signature, "base64url"));
  const body = JSON.parse(Buffer.from(claims, "base64url").toString());
  assert.equal(body.scope, "https://www.googleapis.com/auth/androidpublisher");
  assert.equal(body.exp - body.iat, 3600);
});

test("envoie l'AAB et publie la version sur le test fermé", async () => {
  const google = fakeGoogle();
  const result = await uploadToPlay({ ...base, notes: "Nouveautés", fetchFn: google.fetchFn });
  assert.deepEqual(result, { versionCode: 700, status: "completed" });
  const track = google.calls.find((c) => c.method === "PUT");
  assert.match(track.pathname, /\/tracks\/alpha$/);
  assert.deepEqual(JSON.parse(track.init.body), {
    track: "alpha",
    releases: [{ versionCodes: ["700"], status: "completed", releaseNotes: [{ language: "fr-FR", text: "Nouveautés" }] }],
  });
  assert.equal(google.calls.at(-1).pathname.endsWith(":commit"), true);
  for (const call of google.calls.slice(1)) assert.equal(call.init.headers.authorization, "Bearer jeton-factice");
});

test("se replie sur un brouillon tant que l'app n'est pas publiée", async () => {
  let refused = false;
  const google = fakeGoogle({
    "/tracks/": () => {
      if (refused) return null;
      refused = true;
      return reply(400, { error: { message: "Only releases with status draft may be created on draft app." } });
    },
  });
  const result = await uploadToPlay({ ...base, fetchFn: google.fetchFn });
  assert.equal(result.status, "draft");
  const puts = google.calls.filter((c) => c.method === "PUT").map((c) => JSON.parse(c.init.body).releases[0].status);
  assert.deepEqual(puts, ["completed", "draft"]);
  assert.equal(google.calls.filter((c) => c.pathname.endsWith("/edits")).length, 2);
});

test("explique un numéro de version déjà envoyé, une app absente, un refus", async () => {
  const used = fakeGoogle({ "/bundles": () => reply(403, { error: { message: "APK specifies a version code that has already been used." } }) });
  await assert.rejects(uploadToPlay({ ...base, fetchFn: used.fetchFn }), /Montez la version/);
  const missing = fakeGoogle({ "/edits": () => reply(404, { error: { message: "Package not found: io.example.app." } }) });
  await assert.rejects(uploadToPlay({ ...base, fetchFn: missing.fetchFn }), /premier AAB à la main/);
  const denied = fakeGoogle({ "/token": () => reply(400, { error: "invalid_grant", error_description: "Invalid JWT Signature." }) });
  await assert.rejects(uploadToPlay({ ...base, fetchFn: denied.fetchFn }), /Connexion à Google : Invalid JWT Signature/);
  const broken = fakeGoogle({ ":commit": () => new Response("<html>", { status: 500 }) });
  await assert.rejects(uploadToPlay({ ...base, fetchFn: broken.fetchFn }), /Validation de la modification : HTTP 500/);
  await assert.rejects(uploadToPlay({ ...base, track: "prod", fetchFn: used.fetchFn }), /Piste inconnue/);
});

test("ne met jamais la clé dans un message d'erreur", async () => {
  const google = fakeGoogle({ "/token": () => reply(401, {}) });
  await assert.rejects(uploadToPlay({ ...base, fetchFn: google.fetchFn }), (error) => {
    assert.ok(!error.message.includes("PRIVATE KEY"));
    return true;
  });
});

test("tire les notes de version de la première entrée du CHANGELOG", () => {
  const changelog = [
    "# Journal",
    "",
    "## [0.7.0] - 2026-11-27",
    "",
    "Publication sur les stores.",
    "",
    "### Ajouté",
    "",
    "- Lien vers la **politique** de",
    "  confidentialité (`/confidentialite`).",
    "- [Page](https://example.test) de suppression.",
    "",
    "## [0.6.0] - 2026-11-26",
    "",
    "- Ancien.",
  ].join("\n");
  assert.equal(
    releaseNotes(changelog),
    "Publication sur les stores.\n- Lien vers la politique de confidentialité (/confidentialite).\n- Page de suppression.",
  );
  assert.equal(releaseNotes("rien"), null);
  const long = releaseNotes(`## [1.0.0]\n\n${"mot ".repeat(200)}`);
  assert.ok(long.length <= 500 && long.endsWith("…"));
});
