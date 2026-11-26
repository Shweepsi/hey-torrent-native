# Backend : Worker Cloudflare `heywatchlist-api`

API des comptes de HeyWatchlist, en TypeScript, sur Cloudflare Workers avec une
base D1. Les apps ne lui parlent que par le cœur Rust (`core/src/api.rs`).
Le nom `heywatchlist-api` est fixé : le Worker porte déjà le secret
`FCM_SERVICE_ACCOUNT` (jalon 6), un autre nom le perdrait.

## Ce qu'il fait (jalon 2)

- **Connexion Apple ou Google, sur invitation.** L'app envoie le jeton
  d'identité obtenu du système ; le Worker vérifie sa signature (clés publiques
  d'Apple et de Google), l'émetteur, l'audience, l'expiration et le nonce (un
  nonce ne sert qu'une fois). Une personne inconnue doit donner un code
  d'invitation ; la même adresse e-mail **vérifiée** retrouve le même compte,
  qu'elle vienne d'Apple ou de Google.
- **Premier compte = administrateur**, créé avec le code du secret
  `BOOTSTRAP_CODE` tant qu'aucun administrateur n'existe. Ensuite, ce code ne
  sert plus à rien.
- **Sessions** : jeton d'accès de 15 minutes (JWT HS256) et jeton de
  rafraîchissement renouvelé à chaque usage. Réutiliser un ancien jeton de
  rafraîchissement (vol probable) ferme la session. Une session dure 90 jours
  sans usage.
- **Invitations** : l'administrateur crée un code `XXXX-XXXX-XXXX`, valable
  14 jours et une seule fois (carte « Inviter quelqu'un » de l'APK admin).
- **Suppression du compte** : tout est effacé (sessions et identités par
  `ON DELETE CASCADE`). Si le compte a un lien Apple, le Worker le révoque chez
  Apple avec un code d'autorisation tout juste obtenu par l'app (Apple l'exige).
- Rien n'est journalisé qui identifie une personne ou contient un jeton ; la
  base ne garde que des empreintes SHA-256 des codes, jetons et nonces.

## Routes

| Méthode et chemin | Accès | Rôle |
|---|---|---|
| `GET /v1/health` | libre | `{"status":"ok"}` |
| `GET /v1/config` | libre | Identifiants publics des clients Google (`google_web_client_id`, `google_ios_client_id`, ou `null`) |
| `POST /v1/auth/sign-in` | libre | `{provider, id_token, nonce, invitation_code?, given_name?}` → jetons et profil |
| `POST /v1/auth/refresh` | jeton de rafraîchissement | Nouveaux jetons |
| `POST /v1/auth/sign-out` | jeton de rafraîchissement | Ferme la session (204) |
| `GET /v1/me` | jeton d'accès | Profil : `id`, `role`, `display_name`, `providers` |
| `DELETE /v1/me` | jeton d'accès | Supprime le compte ; `{apple_authorization_code?}` |
| `POST /v1/invitations` | administrateur | Crée une invitation (201, `{code, expires_at}`) |

Les erreurs sont `{"error": "<code>"}`, avec un code stable que le cœur traduit
en type d'erreur : `invalid_request` (400), `invalid_token`,
`unauthorized`, `session_expired` (401), `invitation_required`,
`invitation_invalid`, `forbidden` (403), `not_found` (404),
`provider_unavailable` (503), `internal` (500). Aucun texte affiché.

## Base D1

Base `heywatchlist` (identifiant dans `wrangler.toml`), migrations dans
`migrations/`. Le jalon 2 ne crée que les tables des comptes : `users`,
`identities`, `invitations`, `sessions`, `used_nonces`. Les tables des jalons
suivants (watchlist, demandes, appareils, bibliothèque Plex…) arriveront avec
leur jalon, dans de nouvelles migrations, rattachées à `users` par
`ON DELETE CASCADE`.

## Secrets du Worker

Tous posés par `scripts/configurer-backend.mjs` (voir plus bas), jamais dans
le dépôt.

| Nom | Obligatoire | Contenu |
|---|---|---|
| `SESSION_SIGNING_KEY` | oui | 32 octets aléatoires en base64 : signe les jetons d'accès. Le changer déconnecte tout le monde. |
| `BOOTSTRAP_CODE` | jusqu'au premier compte | Code qui crée le compte administrateur |
| `GOOGLE_WEB_CLIENT_ID` | pour Google sur Android | Client OAuth « Application Web » |
| `GOOGLE_IOS_CLIENT_ID` | pour Google sur iPhone | Client OAuth « iOS » |
| `APPLE_TEAM_ID`, `APPLE_SIGNIN_KEY_ID`, `APPLE_SIGNIN_PRIVATE_KEY` | avant la publication | Clé « Sign in with Apple » : révocation à la suppression d'un compte |
| `FCM_SERVICE_ACCOUNT` | jalon 6 | Déjà posé ; pas encore utilisé |

`APPLE_CLIENT_ID` (l'identifiant de l'app iPhone) est une variable publique de
`wrangler.toml`.

## Mise en place (une fois, sur le PC du propriétaire)

```sh
node backend/scripts/configurer-backend.mjs
```

Le script ouvre la page Cloudflare de création du jeton de la CI avec les bonnes
autorisations déjà cochées (Workers Scripts : Edit, D1 : Edit, Account
Settings : Read), vérifie le jeton, le Worker, la base et l'adresse
`workers.dev`, pose les secrets ci-dessus (sans jamais remplacer un secret
existant), puis crée sur GitHub le secret `CLOUDFLARE_API_TOKEN` et les variables
`CLOUDFLARE_ACCOUNT_ID` et `HEYWATCHLIST_API_URL`. Il affiche le code
administrateur une seule fois et le range dans
`~/HeyWatchlist-secrets/mots-de-passe.txt`. Il se relance sans risque, par
exemple pour ajouter plus tard les clients Google ou la clé Apple.

Les clients Google se créent dans Google Cloud (projet Firebase
`heywatchlist-60448`) > **API et services** > **Identifiants** : un client
« Application Web », deux clients « Android » (`io.github.shweepsi.heywatchlist`
et `io.github.shweepsi.heywatchlist.admin`, avec l'empreinte SHA-1 de la clé de
signature, que le script affiche s'il trouve le keystore) et un client « iOS »
(bundle `io.github.shweepsi.heywatchlist`). L'écran de consentement doit être
« En production » pour que des invités hors de la liste de test se connectent.

## Développement

Node 24 et npm 11 (le `package.json` autorise les scripts d'installation
d'`esbuild` et de `workerd` seulement) :

```sh
cd backend
npm ci
npm run check   # TypeScript
npm test        # Vitest dans workerd, base D1 locale, migrations appliquées
```

Les tests n'appellent ni Cloudflare, ni Apple, ni Google : clés de signature
créées pour le test, `fetch` simulé, heure fixée.

## Déploiement

`.github/workflows/ci-backend.yml` : sur chaque PR qui touche `backend/`,
TypeScript et tests ; sur `main`, en plus, `wrangler d1 migrations apply DB
--remote` puis `wrangler deploy`. Sans `CLOUDFLARE_API_TOKEN` ou
`CLOUDFLARE_ACCOUNT_ID`, le déploiement est ignoré (avec un avertissement), les
tests tournent quand même.

L'adresse publique est `https://heywatchlist-api.<sous-domaine>.workers.dev`.
Elle n'est pas écrite dans le dépôt : la CI la lit dans la variable GitHub
`HEYWATCHLIST_API_URL` et l'inscrit dans les apps à la compilation.
