#!/usr/bin/env node
// Configure le serveur de HeyWatchlist (Worker Cloudflare `heywatchlist-api`)
// et les réglages GitHub dont la CI a besoin pour le déployer (backend/README.md).
//
// À lancer sur le PC du propriétaire, dans PowerShell ou Windows Terminal :
//   node configurer-backend.mjs
//
// Règles de ce script :
// - aucune valeur secrète n'est affichée, journalisée ni passée en argument de
//   commande : elles voyagent dans le corps des requêtes HTTPS vers l'API de
//   Cloudflare, ou par l'entrée standard de `gh` ;
// - seule exception voulue : le code administrateur, affiché une fois sur cet
//   écran pour être tapé dans l'app ; il ne sert plus dès que le compte
//   administrateur existe ;
// - il peut être relancé sans risque : un secret déjà présent sur le Worker est
//   conservé (remplacer la clé des sessions déconnecterait tout le monde) ;
// - il ne dépend que de Node et de `gh` (GitHub CLI), proposé à l'installation
//   avec winget ; `keytool` (JDK) sert seulement, s'il est là, à afficher
//   l'empreinte SHA-1 de la clé Android demandée par Google.
import { spawnSync } from "node:child_process";
import { createPrivateKey, randomBytes, randomInt } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createInterface } from "node:readline";
import { Writable } from "node:stream";
import tls from "node:tls";

// --- Réglages ---------------------------------------------------------------

const IS_WINDOWS = process.platform === "win32";
// Windows cherche d'abord un exécutable dans le dossier courant (souvent
// Téléchargements) : on l'interdit et on lance tout depuis un dossier système.
if (IS_WINDOWS) process.env.NoDefaultCurrentDirectoryInExePath = "1";
const SAFE_CWD = IS_WINDOWS ? path.join(process.env.SystemRoot ?? "C:\\Windows", "System32") : os.homedir();

function parseRepo() {
  const args = process.argv.slice(2);
  if (args.length === 0) return "Shweepsi/hey-torrent-native";
  const value = args[0] === "--repo" ? args[1] : /^--repo=(.+)$/.exec(args[0])?.[1];
  const expected = args[0] === "--repo" ? 2 : 1;
  if (args.length !== expected || !value || !/^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})\/[A-Za-z0-9._-]{1,100}$/.test(value)) {
    process.stderr.write("Usage : node configurer-backend.mjs [--repo PROPRIETAIRE/DEPOT]\n");
    process.exit(2);
  }
  return value;
}

const REPO = parseRepo();
const BUNDLE_ID = "io.github.shweepsi.heywatchlist";
const WORKER = "heywatchlist-api";
// Base D1 déclarée dans backend/wrangler.toml.
const DATABASE_ID = "41769bb4-adeb-41d5-8aeb-7fea6c49ad1f";
const ANDROID_ALIAS = "heywatchlist";
const BACKUP_DIR = path.join(os.homedir(), "HeyWatchlist-secrets");
const STORE_FILE = path.join(BACKUP_DIR, "mots-de-passe.txt");
const KEYSTORE_NAME = "heywatchlist-release.jks";
const KEYSTORE_FILE = path.join(BACKUP_DIR, KEYSTORE_NAME);
const LOGIN_MARKER = path.join(BACKUP_DIR, ".gh-connecte-par-le-script");

const CF_API = "https://api.cloudflare.com/client/v4";
// Jeton de la CI : déployer le Worker, appliquer les migrations D1, lire le compte.
const TOKEN_PERMISSIONS = [
  { key: "workers_scripts", type: "edit" },
  { key: "d1", type: "edit" },
  { key: "account_settings", type: "read" },
];
const TOKEN_PAGE =
  "https://dash.cloudflare.com/profile/api-tokens" +
  `?permissionGroupKeys=${encodeURIComponent(JSON.stringify(TOKEN_PERMISSIONS))}` +
  `&accountId=*&zoneId=all&name=${encodeURIComponent("HeyWatchlist GitHub Actions")}`;
// Même alphabet que les codes d'invitation du serveur (backend/src/crypto.ts).
const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
const GOOGLE_CLIENT = /^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$/;


// Un antivirus qui inspecte HTTPS installe son certificat dans le magasin de
// Windows, que Node ignore par défaut (gh, lui, l'utilise).
if (IS_WINDOWS && typeof tls.setDefaultCACertificates === "function" && typeof tls.getCACertificates === "function") {
  try {
    tls.setDefaultCACertificates([...tls.getCACertificates("default"), ...tls.getCACertificates("system")]);
  } catch {
    // on garde le magasin de certificats de Node
  }
}

// --- Affichage et saisie ----------------------------------------------------

const say = (text = "") => process.stdout.write(`${text}\n`);
const ok = (text) => say(`  ✓ ${text}`);
const note = (text) => say(`    ${text}`);
const warn = (text) => say(`  ⚠ ${text}`);

function die(text) {
  say(`\n✗ ${text}`);
  process.exit(1);
}

// Saisie ligne à ligne. Les lignes arrivées avant la question sont gardées en
// file (un copier-coller de plusieurs lignes ne perd rien) ; la saisie du jeton
// est masquée en coupant l'écho de la console, et rien n'est gardé dans
// l'historique (flèche haut).
const echo = new Writable({
  write(chunk, encoding, done) {
    if (!echo.hidden) process.stdout.write(chunk, encoding);
    done();
  },
});
echo.hidden = false;
// readline lit la largeur de la console sur son flux de sortie pour replier les lignes.
Object.defineProperty(echo, "columns", { get: () => process.stdout.columns });
const reader = createInterface({
  input: process.stdin,
  output: echo,
  terminal: Boolean(process.stdin.isTTY),
  historySize: 0,
});
const queue = [];
let waiting = null;
let inputClosed = false;
reader.on("line", (line) => {
  if (waiting) {
    const resolve = waiting;
    waiting = null;
    resolve(line);
  } else {
    queue.push(line);
  }
});
function interrupted() {
  say("\nInterrompu : relancez le script quand vous voulez, il reprendra où il en était.");
  process.exit(130);
}
reader.on("SIGINT", interrupted); // Ctrl+C pendant une question
process.on("SIGINT", interrupted); // Ctrl+C le reste du temps

// readline met la console en mode brut dès sa création. On ne la garde ainsi que
// pendant une question : sinon Ctrl+C reste muet pendant les commandes, et les
// programmes interactifs lancés ici (gh auth login, winget) ne reçoivent plus Entrée.
function listening(on) {
  if (!process.stdin.isTTY || typeof process.stdin.setRawMode !== "function") return;
  if (on) {
    process.stdin.setRawMode(true);
    reader.resume();
  } else {
    reader.pause();
    process.stdin.setRawMode(false);
  }
}
listening(false);

reader.on("close", () => {
  inputClosed = true;
  if (waiting) die("La saisie a été interrompue.");
});

function nextLine() {
  if (queue.length > 0) return Promise.resolve(queue.shift());
  if (inputClosed) die("La saisie a été interrompue.");
  return new Promise((resolve) => {
    waiting = resolve;
  });
}

async function ask(question, { hidden = false, fallback = "" } = {}) {
  // La question est l'invite de readline : elle survit aux corrections de saisie.
  reader.setPrompt(question);
  echo.hidden = false;
  listening(true);
  reader.prompt();
  echo.hidden = hidden;
  const answer = (await nextLine()).trim();
  listening(false);
  echo.hidden = false;
  if (hidden) say();
  return answer === "" ? fallback : answer;
}

async function confirm(question, defaultYes) {
  const hint = defaultYes ? "(O/n)" : "(o/N)";
  for (;;) {
    const answer = (await ask(`${question} ${hint} `)).toLowerCase();
    if (answer === "") return defaultYes;
    if (["o", "oui", "y", "yes", "ok"].includes(answer)) return true;
    if (["n", "non", "no"].includes(answer)) return false;
    warn("Répondez o (oui) ou n (non).");
  }
}

// Points que l'utilisateur doit vérifier : rappelés à la fin, car les avertissements
// défilent hors de l'écran et le bilan ne doit pas être « tout est en place » à tort.
const toCheck = [];
function flag(text) {
  warn(text);
  toCheck.push(text);
}

async function askValid(question, valid, error, options = {}) {
  for (;;) {
    const answer = await ask(question, options);
    if (valid(answer)) return answer;
    warn(error);
  }
}

// --- Commandes --------------------------------------------------------------

function run(command, args, { input, env, inherit = false, shell = false, cwd = SAFE_CWD, hide = true, timeout = 120_000 } = {}) {
  const result = spawnSync(command, args, {
    input,
    cwd,
    encoding: "utf8",
    stdio: inherit ? "inherit" : ["pipe", "pipe", "pipe"],
    env: env ? { ...process.env, ...env } : process.env,
    shell,
    windowsHide: hide,
    timeout: inherit ? undefined : timeout,
  });
  if (result.signal === "SIGINT" || result.status === 0xc000013a) interrupted();
  const reason = result.error ? `${result.error.code ?? "erreur"} (${result.error.message})` : "";
  return {
    status: result.status ?? -1,
    stdout: (result.stdout ?? "").trim(),
    stderr: [(result.stderr ?? "").trim(), reason].filter(Boolean).join(" "),
  };
}

function subdirectories(dir) {
  try {
    return fs
      .readdirSync(dir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => path.join(dir, entry.name))
      .sort()
      .reverse();
  } catch {
    return [];
  }
}

function findGh() {
  const candidates = ["gh"];
  if (IS_WINDOWS) {
    const roots = [process.env.ProgramFiles, process.env["ProgramFiles(x86)"]];
    if (process.env.LOCALAPPDATA) roots.push(path.join(process.env.LOCALAPPDATA, "Programs"));
    for (const root of roots) {
      if (root) candidates.push(path.join(root, "GitHub CLI", "gh.exe"));
    }
  }
  return candidates.find((candidate) => run(candidate, ["--version"]).status === 0) ?? null;
}

function findKeytool() {
  const exe = IS_WINDOWS ? "keytool.exe" : "keytool";
  const candidates = ["keytool"];
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, "bin", exe));
  if (IS_WINDOWS) {
    for (const root of [process.env.ProgramFiles, process.env["ProgramFiles(x86)"]]) {
      if (!root) continue;
      candidates.push(path.join(root, "Android", "Android Studio", "jbr", "bin", exe));
      for (const vendor of ["Eclipse Adoptium", "Microsoft", "Java", "Zulu", "BellSoft"]) {
        for (const jdk of subdirectories(path.join(root, vendor))) {
          candidates.push(path.join(jdk, "bin", exe));
        }
      }
    }
  }
  return candidates.find((candidate) => run(candidate, ["-help"]).status === 0) ?? null;
}

async function ensureTool(label, find, wingetId, manualHelp) {
  const found = find();
  if (found) return found;
  say(`\n${label} n'est pas installé sur ce PC.`);
  if (!IS_WINDOWS) die(`Installez-le puis relancez le script. ${manualHelp}`);
  if (!(await confirm(`Je l'installe avec winget ?`, true))) {
    die(`Installez-le puis relancez le script : winget install --id ${wingetId} -e`);
  }
  note("Windows va demander l'autorisation d'installer (fenêtre « Contrôle de compte d'utilisateur », parfois derrière celle-ci) : répondez Oui. Comptez 1 à 3 minutes.");
  // Commande en une seule chaîne : l'identifiant est une constante de ce fichier.
  const install = run(
    `winget install --id ${wingetId} -e --accept-source-agreements --accept-package-agreements`,
    [],
    { inherit: true, shell: true },
  );
  const installed = find();
  if (installed) return installed;
  if (install.status !== 0) {
    die(`L'installation a échoué. Installez-le à la main puis relancez le script. ${manualHelp}`);
  }
  die(
    `${label} est installé, mais ce terminal ne le voit pas encore : fermez cette fenêtre, ` +
      `ouvrez-en une nouvelle et relancez la même commande (le script reprend où il en était).`,
  );
}

// --- GitHub -----------------------------------------------------------------

let gh = "gh";

// Toujours github.com, même si l'environnement pointe ailleurs (GH_HOST, GH_REPO).
function ghRun(args, options = {}) {
  return run(gh, args, { ...options, env: { GH_HOST: "github.com", GH_REPO: undefined, ...options.env } });
}

function ensureLoggedIn() {
  if (ghRun(["auth", "status", "--hostname", "github.com"]).status === 0) return;
  say("\nConnexion à GitHub : gh va poser quelques questions en anglais.");
  note("1. « Authenticate Git with your GitHub credentials? » : répondez n (le script n'utilise pas Git).");
  note("2. gh affiche un code (du type ABCD-1234) : copiez-le, puis appuyez sur Entrée pour ouvrir la page GitHub.");
  note("3. Sur la page, collez le code, cliquez « Authorize », puis revenez ici.");
  const clip = ghRun(["auth", "login", "--help"]).stdout.includes("--clipboard") ? ["--clipboard"] : [];
  const login = run(gh, ["auth", "login", "--hostname", "github.com", "--git-protocol", "https", "--web", ...clip], {
    inherit: true,
    env: { GH_HOST: "github.com" },
  });
  if (login.status !== 0 || ghRun(["auth", "status", "--hostname", "github.com"]).status !== 0) {
    if (run("git", ["--version"]).status !== 0) {
      die("Git n'est pas installé et gh en a besoin pour se connecter. Tapez « winget install --id Git.Git -e », fermez cette fenêtre, ouvrez-en une nouvelle et relancez le script.");
    }
    die("La connexion à GitHub a échoué. Relancez le script pour réessayer.");
  }
  // On s'en souvient pour proposer de déconnecter `gh` à la fin : sa connexion
  // donne accès à tous vos dépôts.
  ensureBackupDir();
  fs.writeFileSync(LOGIN_MARKER, "connexion de gh faite par un script de configuration\n");
}

async function ensureRepoAccess() {
  for (;;) {
    const who = ghRun(["api", "user", "--jq", ".login"]);
    const admin = ghRun(["api", `repos/${REPO}`, "--jq", ".permissions.admin"]);
    if (admin.status === 0 && admin.stdout === "true") {
      ok(`GitHub : connecté en tant que ${who.stdout}, accès administrateur à ${REPO}`);
      return;
    }
    if (admin.status !== 0 && !/404|403/.test(admin.stderr)) {
      die(`GitHub ne répond pas (${admin.stderr || "aucune réponse"}). Vérifiez votre connexion Internet puis relancez.`);
    }
    warn(`Le compte connecté à gh (${who.stdout || "inconnu"}) n'est pas administrateur de ${REPO}.`);
    if (!(await confirm("  Me reconnecter avec un autre compte ?", true))) {
      die("Relancez le script avec le compte propriétaire du dépôt.");
    }
    run(gh, ["auth", "logout", "--hostname", "github.com", ...(who.stdout ? ["--user", who.stdout] : [])], {
      inherit: true,
      env: { GH_HOST: "github.com" },
    });
    ensureLoggedIn();
  }
}

function listNames(kind) {
  const result = ghRun([kind, "list", "--repo", REPO]);
  if (result.status !== 0) die(`Impossible de lister les ${kind === "secret" ? "secrets" : "variables"} : ${result.stderr}`);
  return new Set(
    result.stdout
      .split(/\r?\n/)
      .map((line) => line.trim().split(/\s+/)[0])
      .filter(Boolean),
  );
}

function setSecret(name, value) {
  const result = ghRun(["secret", "set", name, "--repo", REPO], { input: value });
  if (result.status !== 0) die(`Échec de l'envoi du secret ${name} : ${result.stderr}`);
  ok(`secret ${name} envoyé`);
}

function setVariable(name, value) {
  const result = ghRun(["variable", "set", name, "--repo", REPO, "--body", value]);
  if (result.status !== 0) die(`Échec de l'envoi de la variable ${name} : ${result.stderr}`);
  ok(`variable ${name} = ${value}`);
}

// --- Sauvegarde locale (phrases secrètes) -----------------------------------

function ensureBackupDir() {
  fs.mkdirSync(BACKUP_DIR, { recursive: true, mode: 0o700 });
}

function readStore() {
  const store = {};
  if (!fs.existsSync(STORE_FILE)) return store;
  for (const line of fs.readFileSync(STORE_FILE, "utf8").split(/\r?\n/)) {
    const match = /^([A-Z_]+)=(.*)$/.exec(line.trim());
    if (match) store[match[1]] = match[2];
  }
  return store;
}

function saveToStore(name, value) {
  ensureBackupDir();
  const store = { ...readStore(), [name]: value };
  const lines = [
    "# Sauvegarde locale des phrases secrètes de HeyWatchlist.",
    "# À copier dans votre gestionnaire de mots de passe, puis à supprimer d'ici.",
    ...Object.entries(store).map(([key, val]) => `${key}=${val}`),
    "",
  ];
  // Écriture en deux temps : une interruption ne laisse pas un fichier tronqué.
  const temporary = `${STORE_FILE}.tmp`;
  fs.writeFileSync(temporary, lines.join("\n"), { mode: 0o600 });
  fs.renameSync(temporary, STORE_FILE);
}

// --- Apple ------------------------------------------------------------------

function downloadDirs() {
  const home = os.homedir();
  const roots = new Set([home]);
  for (const key of ["OneDrive", "OneDriveConsumer", "OneDriveCommercial"]) {
    if (process.env[key]) roots.add(process.env[key]);
  }
  try {
    for (const entry of fs.readdirSync(home, { withFileTypes: true })) {
      if (entry.isDirectory() && /^OneDrive/i.test(entry.name)) roots.add(path.join(home, entry.name));
    }
  } catch {
    // profil illisible : on garde les racines déjà connues
  }
  return [...roots].flatMap((root) => ["Downloads", "Téléchargements"].map((name) => path.join(root, name)));
}

function findP8Candidates() {
  const found = [];
  for (const dir of downloadDirs()) {
    try {
      for (const name of fs.readdirSync(dir)) {
        if (/^AuthKey_[A-Z0-9]{10}\.p8$/.test(name)) {
          const full = path.join(dir, name);
          found.push({ full, time: fs.statSync(full).mtimeMs });
        }
      }
    } catch {
      // dossier absent : on passe au suivant
    }
  }
  return found.sort((a, b) => b.time - a.time).map((entry) => entry.full);
}

function cleanPath(raw) {
  let cleaned = raw.trim().replace(/^&\s+/, "");
  cleaned = cleaned.replace(/^(["'])(.*)\1$/, "$2").trim();
  cleaned = cleaned.replace(/%([^%]+)%/g, (all, name) => process.env[name] ?? all);
  if (cleaned.startsWith("~")) cleaned = path.join(os.homedir(), cleaned.slice(1));
  return cleaned;
}

// Ne garde que le bloc de la clé, et seulement si c'est bien une clé EC P-256 :
// le format des clés .p8 d'Apple.
function readP8(file) {
  let content;
  try {
    if (fs.statSync(file).size > 4096) return null;
    content = fs.readFileSync(file, "utf8");
  } catch {
    return null;
  }
  const block = /-----BEGIN PRIVATE KEY-----[A-Za-z0-9+/=\r\n]+-----END PRIVATE KEY-----/.exec(content)?.[0];
  if (!block) return null;
  try {
    const key = createPrivateKey(block);
    if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1") return null;
  } catch {
    return null;
  }
  return `${block.replace(/\r\n/g, "\n").trim()}\n`;
}
function openFolder(dir) {
  if (IS_WINDOWS) run("explorer", [dir], { hide: false });
  else if (process.platform === "darwin") run("open", [dir]);
}

function openBrowser(url) {
  if (IS_WINDOWS) run("rundll32", ["url.dll,FileProtocolHandler", url], { hide: false });
  else if (process.platform === "darwin") run("open", [url]);
  else run("xdg-open", [url]);
}
async function offerLogout() {
  if (!fs.existsSync(LOGIN_MARKER)) return;
  say("");
  const question = "Déconnecter gh de GitHub maintenant ? (sa connexion donne accès à tous vos dépôts ; recommandé)";
  if (!(await confirm(question, true))) return;
  const result = ghRun(["auth", "logout", "--hostname", "github.com"]);
  if (result.status === 0) {
    fs.rmSync(LOGIN_MARKER, { force: true });
    ok("gh déconnecté");
  } else {
    warn("Déconnexion impossible : lancez « gh auth logout » vous-même.");
  }
}

// --- Cloudflare -------------------------------------------------------------

// Appel à l'API de Cloudflare. Le jeton ne part que dans l'en-tête ; les erreurs
// rapportées ne reprennent que les codes et messages de Cloudflare.
async function cf(token, method, route, body) {
  try {
    const response = await fetch(`${CF_API}${route}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(20_000),
    });
    const data = await response.json().catch(() => null);
    const errors = (data?.errors ?? []).map((e) => `${e.code} ${e.message}`).join(" ; ");
    return { status: response.status, ok: response.ok && data?.success !== false, result: data?.result, errors };
  } catch (error) {
    return { status: 0, ok: false, result: null, errors: error?.name === "TimeoutError" ? "délai dépassé" : "pas de réponse" };
  }
}

async function askCloudflareToken() {
  say("\n[1/5] Cloudflare : jeton pour la CI");
  note("Ce jeton permet à GitHub de mettre le serveur à jour. Il sert aussi, ici, à régler le serveur.");
  note("La page de création s'ouvre avec les bonnes autorisations déjà cochées :");
  note("  Workers Scripts : Edit · D1 : Edit · Account Settings : Read");
  note("1. Vérifiez ces trois lignes, cliquez « Continue to summary » puis « Create Token ».");
  note("2. Copiez le jeton affiché (Cloudflare ne le montre qu'une fois) et collez-le ici.");
  note("Déjà créé lors d'un passage précédent ? Collez le même, ou créez-en un nouveau.");
  note("Ne le collez nulle part ailleurs, en particulier pas dans la discussion avec Claude.");
  if (await confirm("  Ouvrir la page de création du jeton ?", true)) openBrowser(TOKEN_PAGE);
  for (;;) {
    const token = await askValid(
      "  Jeton Cloudflare (la saisie est masquée) : ",
      (value) => /^\S{20,}$/.test(value),
      "Ce n'est pas un jeton Cloudflare : copiez la longue suite de lettres et de chiffres affichée par Cloudflare.",
      { hidden: true },
    );
    const check = await cf(token, "GET", "/user/tokens/verify");
    if (check.ok && check.result?.status === "active") {
      ok("jeton valide");
      return token;
    }
    if (check.status === 0) die(`Cloudflare ne répond pas (${check.errors}). Vérifiez votre connexion Internet puis relancez.`);
    warn("Cloudflare refuse ce jeton (inexistant, expiré ou désactivé). Recommencez la copie.");
  }
}

async function chooseAccount(token) {
  const accounts = await cf(token, "GET", "/accounts?per_page=50");
  if (!accounts.ok || !Array.isArray(accounts.result) || accounts.result.length === 0) {
    die(`Le jeton ne voit aucun compte Cloudflare (${accounts.errors || accounts.status}). Créez-le avec la page proposée : il lui faut « Account Settings : Read ».`);
  }
  if (accounts.result.length === 1) return accounts.result[0];
  say("  Plusieurs comptes Cloudflare :");
  accounts.result.forEach((account, index) => note(`${index + 1}. ${account.name}`));
  const choice = await askValid(
    "  Numéro du compte qui héberge heywatchlist-api : ",
    (value) => Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= accounts.result.length,
    "Tapez un des numéros de la liste.",
  );
  return accounts.result[Number(choice) - 1];
}

// Vérifie ce dont la CI aura besoin, avec les droits du jeton : le Worker et la base.
async function checkCloudflare(token, accountId) {
  say("\n[2/5] Cloudflare : Worker, base de données et adresse");
  const secrets = await cf(token, "GET", `/accounts/${accountId}/workers/scripts/${WORKER}/secrets`);
  if (secrets.status === 404) {
    die(`Le Worker « ${WORKER} » n'existe pas dans ce compte. Créez-le (Workers & Pages > Create > Hello World, nom ${WORKER}) puis relancez.`);
  }
  if (!secrets.ok) die(`Impossible de lire le Worker « ${WORKER} » (${secrets.errors || secrets.status}). Le jeton a-t-il « Workers Scripts : Edit » ?`);
  ok(`Worker ${WORKER} trouvé`);

  const database = await cf(token, "GET", `/accounts/${accountId}/d1/database/${DATABASE_ID}`);
  if (database.status === 403 || database.status === 401) {
    die("Le jeton ne peut pas lire les bases D1 : recréez-le avec « D1 : Edit », puis relancez.");
  }
  if (!database.ok) die(`La base D1 « heywatchlist » est introuvable dans ce compte (${database.errors || database.status}).`);
  ok("base D1 heywatchlist trouvée");

  const subdomain = await cf(token, "GET", `/accounts/${accountId}/workers/subdomain`);
  const name = subdomain.ok ? subdomain.result?.subdomain : null;
  if (!name || !/^[a-z0-9-]+$/.test(name)) {
    die("Ce compte n'a pas encore d'adresse workers.dev : ouvrez Workers & Pages dans Cloudflare, choisissez-en une, puis relancez.");
  }
  const apiUrl = `https://${WORKER}.${name}.workers.dev`;
  ok(`adresse du serveur : ${apiUrl}`);
  return { names: new Set((secrets.result ?? []).map((secret) => secret.name)), apiUrl };
}

async function putWorkerSecret(token, accountId, name, value) {
  const result = await cf(token, "PUT", `/accounts/${accountId}/workers/scripts/${WORKER}/secrets`, {
    name,
    text: value,
    type: "secret_text",
  });
  if (!result.ok) die(`Échec de l'envoi du secret ${name} au Worker (${result.errors || result.status}).`);
  ok(`secret ${name} enregistré sur le Worker`);
}

function newAdminCode() {
  const group = () => Array.from({ length: 4 }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join("");
  return `${group()}-${group()}-${group()}`;
}

// Clé des sessions et code administrateur. Rend le code à afficher, s'il est connu.
async function setupSessionSecrets(token, accountId, names) {
  say("\n[3/5] Serveur : clé des sessions et code administrateur");
  if (names.has("SESSION_SIGNING_KEY")) {
    ok("clé des sessions déjà en place : conservée");
  } else {
    await putWorkerSecret(token, accountId, "SESSION_SIGNING_KEY", randomBytes(32).toString("base64"));
  }

  const saved = readStore().BOOTSTRAP_CODE;
  if (names.has("BOOTSTRAP_CODE")) {
    if (saved) {
      ok("code administrateur déjà en place (rappelé à la fin)");
      return saved;
    }
    warn("Un code administrateur existe sur le serveur, mais pas sur ce PC.");
    if (!(await confirm("  En créer un nouveau ? (sans effet si votre compte administrateur existe déjà)", true))) return null;
  }
  const code = newAdminCode();
  // Sauvegardé avant l'envoi : une coupure entre les deux ne le perd pas.
  saveToStore("BOOTSTRAP_CODE", code);
  await putWorkerSecret(token, accountId, "BOOTSTRAP_CODE", code);
  return code;
}

const GOOGLE_PROJECT = "heywatchlist-60448";
const googlePage = (page) => `https://console.cloud.google.com/auth/${page}?project=${GOOGLE_PROJECT}`;

// Attend que la personne ait fini dans le navigateur.
async function waitDone(text = "  Appuyez sur Entrée quand c'est fait. ") {
  await ask(text);
}

async function askGoogleClient(label) {
  return askValid(
    `  Collez l'identifiant du client ${label} : `,
    (answer) => answer === "" || GOOGLE_CLIENT.test(answer),
    "Format attendu : 123456789-abc….apps.googleusercontent.com (bouton « copier » à côté de « ID client »), ou Entrée pour passer.",
  );
}

// Empreinte de la clé de signature Android. Propose d'installer Java au besoin.
async function androidSha1() {
  const password = readStore().ANDROID_KEYSTORE_PASSWORD;
  if (!password || !fs.existsSync(KEYSTORE_FILE)) {
    flag(`Google sur Android : keystore introuvable (${KEYSTORE_FILE}) ; lancez d'abord configurer-secrets.mjs.`);
    return null;
  }
  const keytool = await ensureTool("Java (keytool)", findKeytool, "EclipseAdoptium.Temurin.17.JDK", "Il faut un JDK 17 : https://adoptium.net");
  const listing = run(keytool, ["-list", "-v", "-keystore", KEYSTORE_NAME, "-alias", ANDROID_ALIAS, "-storepass:env", "HW_KEYSTORE_PASSWORD"], {
    cwd: BACKUP_DIR,
    env: { HW_KEYSTORE_PASSWORD: password },
  });
  const sha1 = /SHA-?1:\s*([0-9A-F]{2}(?::[0-9A-F]{2}){19})/.exec(listing.stdout)?.[1] ?? null;
  if (!sha1) flag("Google sur Android : empreinte SHA-1 illisible dans le keystore.");
  return sha1;
}

async function setupGoogle(token, accountId, names) {
  say("\n[4/5] Connexion avec Google (facultatif : Apple marche sans)");
  if (["GOOGLE_WEB_CLIENT_ID", "GOOGLE_IOS_CLIENT_ID"].every((name) => names.has(name))) {
    ok("clients Google déjà en place : conservés");
    return;
  }
  note("Environ 5 minutes dans la console Google. Le script ouvre chaque page et vous dit quoi cliquer.");
  if (!(await confirm("  Configurer Google maintenant ?", false))) {
    flag("Google : à configurer plus tard (relancez ce script et répondez o).");
    return;
  }

  say("\n  Étape 1/4 : écran de consentement");
  note(`Vérifiez en haut de la page que le projet est ${GOOGLE_PROJECT}.`);
  note("Si un bouton « Commencer » apparaît : nom de l'app HeyWatchlist, votre e-mail d'assistance,");
  note("Suivant, cible « Externe », Suivant, votre e-mail de contact, Suivant, cochez l'accord, Créer.");
  note("Si la page affiche déjà un tableau de bord, il n'y a rien à faire ici.");
  openBrowser(googlePage("overview"));
  await waitDone();

  say("\n  Étape 2/4 : ouvrir l'app à tous les comptes Google");
  note("Sur la page « Audience » : bouton « Publier l'application », puis Confirmer.");
  note("(Aucune validation Google : l'app ne demande que le nom et l'adresse e-mail.)");
  note("Si l'état affiché est déjà « En production », il n'y a rien à faire.");
  openBrowser(googlePage("audience"));
  await waitDone();

  say("\n  Étape 3/4 : clients pour le serveur et l'iPhone");
  note("Page « Clients » : « + Créer un client ».");
  openBrowser(googlePage("clients"));
  for (const [name, label, steps] of [
    ["GOOGLE_WEB_CLIENT_ID", "« Application Web »", [
      "Type d'application : « Application Web », nom : HeyWatchlist serveur. Ne remplissez rien d'autre, Créer.",
      "Un client « Web client (auto created by Google Service) » existe déjà ? Il convient aussi : ouvrez-le.",
    ]],
    ["GOOGLE_IOS_CLIENT_ID", "« iOS »", [
      `Nouveau client, type « iOS », nom : HeyWatchlist iPhone, ID du bundle : ${BUNDLE_ID}. Le reste vide, Créer.`,
    ]],
  ]) {
    if (names.has(name)) {
      ok(`client ${label} déjà en place : conservé`);
      continue;
    }
    for (const step of steps) note(step);
    note("Google affiche alors l'« ID client » : copiez-le.");
    const value = await askGoogleClient(label);
    if (value === "") {
      flag(`Google : client ${label} à ajouter plus tard (relancez ce script).`);
      continue;
    }
    await putWorkerSecret(token, accountId, name, value);
  }

  say("\n  Étape 4/4 : clients Android (connexion Google sur Android)");
  const sha1 = await androidSha1();
  if (!sha1) return;
  note("Encore « + Créer un client », deux fois, type « Android » :");
  note(`  1. nom HeyWatchlist Android, nom du paquet ${BUNDLE_ID}`);
  note(`  2. nom HeyWatchlist Admin, nom du paquet ${BUNDLE_ID}.admin`);
  note("Empreinte SHA-1 pour les deux (pas secrète, copiez-la d'ici) :");
  say(`\n      ${sha1}\n`);
  note("Rien à copier ensuite : il suffit que ces deux clients existent.");
  await waitDone();
  ok("Google configuré");
}

async function appleTeamId() {
  const variable = ghRun(["variable", "get", "APPLE_TEAM_ID", "--repo", REPO]);
  if (variable.status === 0 && /^[A-Z0-9]{10}$/.test(variable.stdout)) return variable.stdout;
  return askValid(
    "  Team ID Apple (10 caractères, developer.apple.com > Membership details) : ",
    (value) => /^[A-Z0-9]{10}$/.test(value),
    "Le Team ID fait 10 lettres majuscules et chiffres.",
  );
}

async function chooseSignInKey() {
  const candidates = findP8Candidates();
  if (candidates.length > 0) {
    say("  Fichiers de clé Apple trouvés :");
    candidates.forEach((file, index) => note(`${index + 1}. ${path.basename(file)} (${path.dirname(file)})`));
  }
  for (;;) {
    const answer = await ask(
      candidates.length > 0
        ? "  Numéro de la clé « Sign in with Apple », ou chemin du fichier .p8 : "
        : "  Chemin du fichier .p8 téléchargé (glissez-le dans cette fenêtre) : ",
    );
    const index = Number(answer);
    const file = Number.isInteger(index) && index >= 1 && index <= candidates.length ? candidates[index - 1] : cleanPath(answer);
    const keyId = /^AuthKey_([A-Z0-9]{10})\.p8$/.exec(path.basename(file))?.[1];
    const pem = readP8(file);
    if (keyId && pem) return { keyId, pem };
    warn("Ce n'est pas une clé Apple lisible (fichier AuthKey_XXXXXXXXXX.p8 attendu).");
  }
}

async function setupApple(token, accountId, names) {
  say("\n[5/5] Apple : révocation à la suppression d'un compte");
  const wanted = ["APPLE_TEAM_ID", "APPLE_SIGNIN_KEY_ID", "APPLE_SIGNIN_PRIVATE_KEY"];
  if (wanted.every((name) => names.has(name))) {
    ok("clé « Sign in with Apple » déjà en place : conservée");
    return;
  }
  note("Apple exige que la suppression d'un compte coupe aussi le lien « Se connecter avec Apple ».");
  note("Il faut pour cela une clé dédiée, différente de la clé App Store Connect. Pas utile pour tester.");
  if (!(await confirm("  La créer maintenant ? (sinon : plus tard, avant la publication)", false))) {
    flag("Apple : clé « Sign in with Apple » à ajouter avant la publication (relancez ce script).");
    return;
  }
  note("La page de création de clé s'ouvre :");
  note("1. Key Name : HeyWatchlist Sign in ; cochez « Sign in with Apple », puis « Configure » à côté.");
  note(`2. Primary App ID : ${BUNDLE_ID}, Save, Continue, Register.`);
  note("3. Cliquez « Download » : le fichier AuthKey_XXXXXXXXXX.p8 va dans Téléchargements (une seule fois possible).");
  note(`(${BUNDLE_ID} absent de la liste ? La CI iOS l'activera au prochain envoi TestFlight : revenez après.)`);
  openBrowser("https://developer.apple.com/account/resources/authkeys/add");
  await waitDone("  Appuyez sur Entrée une fois le fichier téléchargé. ");
  const { keyId, pem } = await chooseSignInKey();
  const teamId = await appleTeamId();
  await putWorkerSecret(token, accountId, "APPLE_TEAM_ID", teamId);
  await putWorkerSecret(token, accountId, "APPLE_SIGNIN_KEY_ID", keyId);
  await putWorkerSecret(token, accountId, "APPLE_SIGNIN_PRIVATE_KEY", pem);
}

// --- Programme --------------------------------------------------------------

async function main() {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    die("Lancez ce script directement dans PowerShell ou Windows Terminal (pas depuis Git Bash, un bouton « Run » d'éditeur ou avec une redirection) : sinon la saisie du jeton ne peut pas être masquée.");
  }
  if (process.env.NODE_TLS_REJECT_UNAUTHORIZED === "0") {
    die("La variable NODE_TLS_REJECT_UNAUTHORIZED=0 est définie : elle désactive la vérification des connexions sécurisées. Supprimez-la (PowerShell : Remove-Item Env:NODE_TLS_REJECT_UNAUTHORIZED) puis relancez.");
  }
  say("HeyWatchlist : configuration du serveur");
  say(`Dépôt : ${REPO}. Aucune valeur secrète n'est affichée ni envoyée ailleurs que chez Cloudflare et GitHub.\n`);

  gh = await ensureTool("GitHub CLI (gh)", findGh, "GitHub.cli", "https://cli.github.com");
  ensureLoggedIn();
  await ensureRepoAccess();

  const token = await askCloudflareToken();
  const account = await chooseAccount(token);
  ok(`compte Cloudflare : ${account.name}`);
  const { names, apiUrl } = await checkCloudflare(token, account.id);
  const code = await setupSessionSecrets(token, account.id, names);
  await setupGoogle(token, account.id, names);
  await setupApple(token, account.id, names);

  say("\nGitHub : réglages du déploiement");
  setSecret("CLOUDFLARE_API_TOKEN", token);
  setVariable("CLOUDFLARE_ACCOUNT_ID", account.id);
  setVariable("HEYWATCHLIST_API_URL", apiUrl);

  if (toCheck.length > 0) {
    say("\nÀ faire plus tard :");
    for (const text of toCheck) note(`- ${text}`);
  }
  say("\n✓ Serveur configuré. GitHub le déploiera à la fusion de la pull request du jalon 2.");
  if (code) {
    say("\nVotre code administrateur, à taper une seule fois dans l'app, à votre première connexion :");
    say(`\n    ${code}\n`);
    note(`Il est aussi dans ${STORE_FILE}.`);
    note("Ne l'envoyez à personne, pas même dans la discussion avec Claude : il crée le compte administrateur.");
    note("Une fois votre compte créé, il ne sert plus à rien.");
  }
  await offerLogout();
  say("\nRevenez dans la conversation et écrivez « serveur OK »" + (toCheck.length > 0 ? " (la liste « À faire plus tard » peut attendre)." : "."));
}

try {
  await main();
} catch (error) {
  say(`\n✗ Erreur inattendue (${error?.code ?? "?"}) : ${error?.message ?? error}`);
  say("Relancez le script : il reprend où il en était.");
  process.exitCode = 1;
} finally {
  reader.close();
}
