/**
 * Pages publiques exigées par les stores : politique de confidentialité
 * (`/confidentialite`, liée depuis les Réglages des apps et les fiches App Store
 * et Google Play) et suppression du compte (`/suppression-du-compte`, demandée
 * par la section « Sécurité des données » de Google Play).
 *
 * Elles décrivent ce que fait réellement le code : à relire à chaque nouvelle
 * donnée collectée (`docs/PUBLICATION.md`). Le contact du responsable vient de
 * la variable `PRIVACY_CONTACT` du Worker ; sans elle, la page renvoie vers
 * l'administrateur du serveur.
 */
import type { Env } from "./env";

export const PRIVACY_PATH = "/confidentialite";
export const ACCOUNT_DELETION_PATH = "/suppression-du-compte";

/** Date de la dernière modification du texte, affichée en tête des pages. */
const UPDATED = "27 novembre 2026";

function escape(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function contact(env: Env): string {
  const value = env.PRIVACY_CONTACT?.trim();
  if (!value) return "l’administrateur du serveur, qui valide les accès à l’app";
  const safe = escape(value);
  return value.includes("@") && !value.includes(" ") ? `<a href="mailto:${safe}">${safe}</a>` : safe;
}

function page(title: string, body: string): Response {
  const html = `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · HeyWatchlist</title>
<style>
:root { color-scheme: light dark; }
body { font: 16px/1.6 system-ui, sans-serif; max-width: 42rem; margin: 0 auto; padding: 2rem 1rem; }
h1 { font-size: 1.6rem; } h2 { font-size: 1.15rem; margin-top: 2rem; }
small { opacity: .7; }
</style>
</head>
<body>
<h1>${title}</h1>
<p><small>HeyWatchlist · mise à jour le ${UPDATED}</small></p>
${body}
</body>
</html>
`;
  return new Response(html, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "public, max-age=3600",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'",
      "x-content-type-options": "nosniff",
    },
  });
}

export function privacyPolicy(env: Env): Response {
  const who = contact(env);
  return page(
    "Politique de confidentialité",
    `<p>HeyWatchlist permet aux proches d’un administrateur de découvrir des films et des séries, de les
demander à son serveur Plex et d’être prévenus quand ils sont disponibles. Seules les personnes que
l’administrateur accepte y ont accès. Le responsable des données est l’administrateur du serveur ; pour toute question : ${who}.</p>

<h2>Données collectées</h2>
<ul>
<li><strong>Compte</strong> : l’identifiant que Apple ou Google transmet à la connexion, votre adresse
e-mail si le fournisseur l’a vérifiée (elle sert seulement à relier un compte Apple et un compte Google
de la même personne) et votre prénom, ainsi que l’état de votre accès (en attente, accepté, refusé).
L’administrateur peut aussi enregistrer d’avance l’adresse e-mail d’une personne pour l’accepter
directement.</li>
<li><strong>Watchlist et demandes</strong> : les titres que vous ajoutez, les saisons choisies, leur
état, et les dates d’ajout.</li>
<li><strong>Notifications</strong> : le jeton de notification de votre appareil (Apple ou Google), pour
vous prévenir qu’un titre demandé est arrivé ou que votre accès est validé.</li>
<li><strong>Diagnostics de plantage</strong> (Android) : en cas de plantage, Firebase Crashlytics (Google)
reçoit la trace technique de l’erreur, le modèle de l’appareil, la version d’Android et de l’app. Aucune
donnée de compte n’y est jointe.</li>
</ul>
<p>Aucune publicité, aucun pistage entre apps ou sites, aucune revente de données.</p>

<h2>Où sont-elles gardées</h2>
<p>Sur les serveurs Cloudflare (Worker et base D1) de l’administrateur, transmises chiffrées (HTTPS).
Les sessions sont protégées par des jetons dont seul un condensé est gardé. Les affiches et les fiches
des films viennent de TMDB : votre appareil les télécharge directement depuis TMDB, qui voit alors son
adresse IP. Les notifications passent par Apple (APNs) ou Google (Firebase Cloud Messaging).</p>

<h2>Qui y a accès</h2>
<p>L’administrateur voit les demandes d’accès, les comptes (prénom, adresse e-mail), et les demandes de
titres avec le prénom de qui les a faites. Personne d’autre, sauf les
prestataires techniques cités ci-dessus, pour le seul fonctionnement du service.</p>

<h2>Durée et suppression</h2>
<p>Les données sont gardées tant que le compte existe. « Supprimer mon compte », dans les Réglages de
l’app, efface immédiatement le compte, sa watchlist, ses sessions et ses appareils ; les demandes que
plus personne n’attend sont effacées avec lui. Voir aussi
<a href="${ACCOUNT_DELETION_PATH}">la suppression du compte</a>.</p>

<h2>Vos droits</h2>
<p>Vous pouvez demander l’accès à vos données, leur rectification ou leur effacement en écrivant à ${who}.
Vous pouvez aussi saisir la CNIL (cnil.fr).</p>

<h2>Contenu</h2>
<p>HeyWatchlist n’héberge, ne fournit et ne recommande aucun contenu à télécharger. Les informations sur
les films et séries viennent de TMDB. Cette app utilise l’API de TMDB sans être approuvée ni certifiée
par TMDB.</p>`,
  );
}

export function accountDeletion(env: Env): Response {
  const who = contact(env);
  return page(
    "Supprimer votre compte HeyWatchlist",
    `<h2>Depuis l’app</h2>
<ol>
<li>Ouvrez HeyWatchlist et allez dans l’onglet <strong>Réglages</strong>.</li>
<li>Touchez <strong>Supprimer mon compte</strong>, puis confirmez.</li>
</ol>
<h2>Sans l’app</h2>
<p>Écrivez à ${who} en indiquant l’adresse e-mail de votre compte Apple ou Google : le compte est supprimé
sous 30 jours.</p>
<h2>Ce qui est supprimé</h2>
<p>Le compte (identifiant Apple ou Google, adresse e-mail, prénom), la watchlist, les sessions et les
jetons de notification, immédiatement et définitivement. Les demandes que plus personne n’attend sont
supprimées aussi. Une demande qu’une autre personne attend toujours reste, sans lien avec vous.</p>`,
  );
}
