#!/usr/bin/env node
// Envoie l'AAB public sur une piste Google Play (test fermé par défaut), par
// l'API Google Play Developer v3, sans dépendance : Node seul.
//
// Usage : node play-upload.mjs <fichier.aab>
// Environnement :
//   PLAY_SERVICE_ACCOUNT_JSON  clé JSON du compte de service (brute ou en base64). Secret.
//   PLAY_PACKAGE_NAME          nom du paquet (io.github.shweepsi.heywatchlist)
//   PLAY_TRACK                 piste : alpha (test fermé, défaut), internal, beta, production
//   PLAY_CHANGELOG             facultatif : CHANGELOG.md dont la première entrée devient
//                              les notes de version (fr-FR)
//
// Règles : la clé n'est jamais affichée ; seuls les messages d'erreur de
// Google, qui n'en contiennent pas, vont dans le journal. Tant que l'app n'a
// jamais été publiée, Google n'accepte qu'une version « brouillon » : le script
// se replie alors sur `draft` et le dit (la publier depuis la Play Console).
import { createSign } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications";
const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const TRACKS = new Set(["internal", "alpha", "beta", "production"]);
const NOTES_MAX = 500;

export class PlayError extends Error {}
/** Refus de Google tant que l'app n'a jamais été publiée : brouillon seulement. */
class DraftOnly extends PlayError {}

/** Clé du compte de service : JSON brut ou base64, avec les champs utiles. */
export function readServiceAccount(raw) {
  const text = String(raw ?? "").trim();
  if (!text) throw new PlayError("PLAY_SERVICE_ACCOUNT_JSON est vide.");
  const json = text.startsWith("{") ? text : Buffer.from(text, "base64").toString("utf8");
  let key;
  try {
    key = JSON.parse(json);
  } catch {
    throw new PlayError("PLAY_SERVICE_ACCOUNT_JSON n'est pas un JSON lisible (ni brut ni en base64).");
  }
  if (key?.type !== "service_account" || !key.client_email || !key.private_key) {
    throw new PlayError("PLAY_SERVICE_ACCOUNT_JSON n'est pas une clé de compte de service Google.");
  }
  return { email: key.client_email, privateKey: key.private_key, tokenUri: key.token_uri || "https://oauth2.googleapis.com/token" };
}

const base64url = (value) => Buffer.from(value).toString("base64url");

/** Assertion JWT signée (RS256) échangée contre un jeton d'accès OAuth. */
export function signedAssertion(account, now = Math.floor(Date.now() / 1000)) {
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(
    JSON.stringify({ iss: account.email, scope: SCOPE, aud: account.tokenUri, iat: now, exp: now + 3600 }),
  );
  const signer = createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  return `${header}.${claims}.${signer.sign(account.privateKey, "base64url")}`;
}

/** Message d'erreur de Google, sans rien d'autre de la réponse. */
async function googleMessage(response) {
  try {
    const body = await response.json();
    return body?.error?.message || body?.error_description || `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
}

async function expectOk(response, step) {
  if (response.ok) return response.status === 204 ? {} : response.json();
  const message = await googleMessage(response);
  if (/draft app/i.test(message)) throw new DraftOnly(message);
  if (/version code .*already been used/i.test(message)) {
    throw new PlayError(
      `${step} : ce numéro de version a déjà été envoyé. Montez la version (Cargo.toml) avant un nouvel envoi. (${message})`,
    );
  }
  if (response.status === 404 && /package/i.test(message)) {
    throw new PlayError(
      `${step} : app introuvable sur Google Play. Créez-la dans la Play Console et envoyez le premier AAB à la main (docs/PUBLICATION.md). (${message})`,
    );
  }
  throw new PlayError(`${step} : ${message}`);
}


export async function accessToken(account, fetchFn, now) {
  const response = await fetchFn(account.tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: signedAssertion(account, now),
    }).toString(),
  });
  const body = await expectOk(response, "Connexion à Google");
  if (!body.access_token) throw new PlayError("Connexion à Google : pas de jeton d'accès.");
  return body.access_token;
}

/** Première entrée du CHANGELOG, en texte simple, tronquée à 500 caractères. */
export function releaseNotes(changelog) {
  const lines = String(changelog ?? "").split("\n");
  const start = lines.findIndex((line) => line.startsWith("## ["));
  if (start < 0) return null;
  const end = lines.findIndex((line, index) => index > start && line.startsWith("## "));
  // Un bloc par paragraphe ou par puce ; les lignes de suite y sont recollées.
  const blocks = [];
  let current = null;
  for (const raw of lines.slice(start + 1, end < 0 ? undefined : end)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) {
      current = null;
    } else if (line.startsWith("- ") || current === null) {
      current = [line];
      blocks.push(current);
    } else {
      current.push(line);
    }
  }
  const text = blocks
    .map((block) => block.join(" "))
    .join("\n")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/\*\*([^*]*)\*\*/g, "$1")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1");
  if (!text) return null;
  if (text.length <= NOTES_MAX) return text;
  const cut = text.slice(0, NOTES_MAX - 1);
  return `${cut.slice(0, Math.max(cut.lastIndexOf(" "), 1))}…`;
}

async function oneEdit({ packageName, track, aab, notes, status, token, fetchFn }) {
  const auth = { authorization: `Bearer ${token}` };
  const app = `${API}/${encodeURIComponent(packageName)}`;
  const edit = await expectOk(await fetchFn(`${app}/edits`, { method: "POST", headers: auth }), "Ouverture de la modification");
  const editUrl = `${app}/edits/${encodeURIComponent(edit.id)}`;
  const bundle = await expectOk(
    await fetchFn(`${UPLOAD}/${encodeURIComponent(packageName)}/edits/${encodeURIComponent(edit.id)}/bundles?uploadType=media`, {
      method: "POST",
      headers: { ...auth, "content-type": "application/octet-stream" },
      body: aab,
    }),
    "Envoi de l'AAB",
  );
  const release = { versionCodes: [String(bundle.versionCode)], status };
  if (notes) release.releaseNotes = [{ language: "fr-FR", text: notes }];
  await expectOk(
    await fetchFn(`${editUrl}/tracks/${encodeURIComponent(track)}`, {
      method: "PUT",
      headers: { ...auth, "content-type": "application/json" },
      body: JSON.stringify({ track, releases: [release] }),
    }),
    "Mise à jour de la piste",
  );
  await expectOk(await fetchFn(`${editUrl}:commit`, { method: "POST", headers: auth }), "Validation de la modification");
  return { versionCode: bundle.versionCode, status };
}

/** Envoie l'AAB et publie la version sur la piste ; renvoie son numéro et son statut. */
export async function uploadToPlay({ serviceAccount, packageName, track = "alpha", aab, notes, fetchFn = fetch, now }) {
  if (!packageName) throw new PlayError("PLAY_PACKAGE_NAME est vide.");
  if (!TRACKS.has(track)) throw new PlayError(`Piste inconnue : ${track} (internal, alpha, beta ou production).`);
  const account = readServiceAccount(serviceAccount);
  const token = await accessToken(account, fetchFn, now);
  const options = { packageName, track, aab, notes, token, fetchFn };
  try {
    return await oneEdit({ ...options, status: "completed" });
  } catch (error) {
    if (!(error instanceof DraftOnly)) throw error;
    // App jamais publiée : Google n'accepte qu'un brouillon. La modification
    // précédente n'a pas été validée, on la reprend à neuf.
    return oneEdit({ ...options, status: "draft" });
  }
}

async function main() {
  const [file] = process.argv.slice(2);
  if (!file) throw new PlayError("Usage : node play-upload.mjs <fichier.aab>");
  const changelog = process.env.PLAY_CHANGELOG ? await readFile(process.env.PLAY_CHANGELOG, "utf8") : "";
  const result = await uploadToPlay({
    serviceAccount: process.env.PLAY_SERVICE_ACCOUNT_JSON,
    packageName: process.env.PLAY_PACKAGE_NAME,
    track: process.env.PLAY_TRACK || "alpha",
    aab: await readFile(file),
    notes: releaseNotes(changelog),
  });
  const summary =
    result.status === "draft"
      ? `Version ${result.versionCode} envoyée en brouillon : l'app n'a encore jamais été publiée. Terminez la configuration et publiez-la dans la Play Console (Tester et publier > Test fermé).`
      : `Version ${result.versionCode} envoyée sur la piste « ${process.env.PLAY_TRACK || "alpha"} » ; elle part en examen chez Google.`;
  console.log(result.status === "draft" ? `::warning title=Google Play::${summary}` : `::notice title=Google Play::${summary}`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    const message = error instanceof PlayError ? error.message : `erreur inattendue (${error?.name ?? typeof error})`;
    console.log(`::error title=Google Play::${message}`);
    process.exitCode = 1;
  });
}
