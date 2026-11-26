# Backend : Worker Cloudflare `heywatchlist-api`

API de HeyWatchlist (comptes, relais du catalogue TMDB), en TypeScript, sur
Cloudflare Workers avec une base D1. Les apps ne lui parlent que par le cœur Rust (`core/src/api.rs`).
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

## Ce qu'il fait en plus (jalon 3)

- **Relais du catalogue TMDB** (`GET /v1/tmdb/<chemin>`) : les apps n'ont pas de
  clé TMDB ; le Worker appelle TMDB avec la sienne, sur une liste blanche stricte
  (voir « Relais TMDB » plus bas), et garde les réponses dans D1.

## Ce qu'il fait en plus (jalon 4)

- **Watchlist et demandes** : ajouter un titre crée la demande, ou rattache la
  personne à celle qui existe (une seule par titre). Le Worker lit le titre et
  l'affiche chez TMDB ; le téléphone n'envoie que le type, l'identifiant et,
  pour une série, les saisons (`null` : série entière). Retirer un titre
  supprime la demande seulement si plus personne ne la suit et qu'elle est
  encore `requested` ou `refused`.

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
| `GET /v1/watchlist` | jeton d'accès | `{items}` : ma watchlist, la plus récente d'abord, avec l'état de chaque demande |
| `PUT /v1/watchlist/{movie\|tv}/{id}` | jeton d'accès | Ajoute ou met à jour un titre ; `{seasons?}` → `{item}` |
| `DELETE /v1/watchlist/{movie\|tv}/{id}` | jeton d'accès | Retire un titre (204) |
| `GET /v1/tmdb/<chemin>` | jeton d'accès | Relais du catalogue TMDB : liste blanche de chemins et de paramètres (voir « Relais TMDB »), réponse TMDB telle quelle |

Les erreurs sont `{"error": "<code>"}`, avec un code stable que le cœur traduit
en type d'erreur : `invalid_request` (400), `invalid_token`,
`unauthorized`, `session_expired` (401), `invitation_required`,
`invitation_invalid`, `forbidden` (403), `not_found` (404),
`provider_unavailable` (503), `internal` (500). Aucun texte affiché.

## Relais TMDB

`GET /v1/tmdb/<chemin>` avec `Authorization: Bearer <jeton d'accès>`, vérifié
avant tout accès au cache ou au réseau (sinon 401 `unauthorized`). Seuls ces
chemins existent ; tout le reste est `404 not_found`, jamais relayé.

| Chemin après `/v1/tmdb/` | Paramètres acceptés | Cache |
|---|---|---|
| `trending/{all\|movie\|tv}/{day\|week}` | `page` | 1 h |
| `movie/{popular\|now_playing\|upcoming\|top_rated}` | `page` | 6 h |
| `tv/{popular\|on_the_air\|airing_today\|top_rated}` | `page` | 6 h |
| `genre/{movie\|tv}/list` | | 7 j |
| `discover/{movie\|tv}` | `page`, `with_genres`, `sort_by` | 6 h |
| `search/multi` | `query` (obligatoire), `page` | aucun |
| `movie/{id}`, `tv/{id}` | | 24 h |
| `tv/{id}/season/{n}` | | 12 h |

- `page` : entier de 1 à 500 (1 par défaut). `with_genres` : 10 identifiants
  au plus, séparés par `,` (et) ou `|` (ou), pas les deux ; le Worker les trie.
  `sort_by` : `popularity.desc`, `vote_average.desc`,
  `primary_release_date.desc` ou `first_air_date.desc`. `query` : 1 à 100
  caractères, espaces de bord retirés. `id` : au plus 10 chiffres ; `n` : 0 à 99.
- Un paramètre accepté mais invalide ou hors bornes (ou répété) donne `400
  invalid_request`. Un paramètre que la route n'accepte pas est ignoré, jamais
  transmis : le client ne peut pas changer la langue, ni ajouter ses propres
  réglages.
- Le Worker impose `language=fr-FR` partout, `include_adult=false` (tendances,
  listes, `discover`, recherche), `region=FR` (listes de films et de séries),
  `watch_region=FR` (`discover`), et pour une fiche
  `append_to_response=credits,videos,watch/providers,similar` et
  `include_video_language=fr,en,null`.
- Réponse : le JSON de TMDB, tel quel (200). TMDB répond 404 : `404 not_found`.
  Tout autre échec (429, 5xx, autre refus, délai de 10 s dépassé, réseau, JSON
  illisible, clé `TMDB_API_KEY` absente) : `503 provider_unavailable`, sans
  jamais mettre en cache. Pas de nouveau code d'erreur.
- **Clé TMDB** : secret `TMDB_API_KEY`. Un jeton v4 (commence par `eyJ`) part dans
  l'en-tête `Authorization: Bearer` ; sinon c'est une clé v3, dans le paramètre
  `api_key`. Elle n'est jamais journalisée, jamais dans une clé de cache, jamais
  dans une réponse.
- **Cache D1** (table `tmdb_cache`) : la Cache API ne fonctionne pas sur
  `workers.dev`. La clé est le chemin TMDB et ses paramètres triés (sans clé
  d'API), partagée par tous les comptes. Une entrée non expirée est servie sans
  appeler TMDB ; sinon le Worker appelle TMDB, écrit la réponse
  (`INSERT OR REPLACE`) et efface au plus 20 entrées expirées. Une panne de D1
  n'est jamais fatale : la réponse de TMDB est servie. Les entrées expirées ne
  sont pas servies en secours (le cœur le fait de son côté, hors ligne).

## Base D1

Base `heywatchlist` (identifiant dans `wrangler.toml`), migrations dans
`migrations/`. Le jalon 2 crée les tables des comptes : `users`, `identities`,
`invitations`, `sessions`, `used_nonces`. Le jalon 3 ajoute `tmdb_cache`
(`0002_tmdb_cache.sql`), des données publiques sans lien avec `users`. Le
jalon 4 ajoute `requests` (une demande par titre, son état) et
`watchlist_items` (rattachées à `users` et à `requests`,
`0003_watchlist.sql`). Les tables des jalons suivants (appareils,
bibliothèque Plex…) arriveront avec leur jalon, dans de nouvelles migrations, rattachées à
`users` par `ON DELETE CASCADE`.

## Secrets du Worker

Tous posés par `scripts/configurer-backend.mjs` (voir plus bas), jamais dans
le dépôt.

| Nom | Obligatoire | Contenu |
|---|---|---|
| `SESSION_SIGNING_KEY` | oui | 32 octets aléatoires en base64 : signe les jetons d'accès. Le changer déconnecte tout le monde. |
| `BOOTSTRAP_CODE` | jusqu'au premier compte | Code qui crée le compte administrateur |
| `GOOGLE_WEB_CLIENT_ID` | pour Google sur Android | Client OAuth « Application Web » |
| `GOOGLE_IOS_CLIENT_ID` | pour Google sur iPhone | Client OAuth « iOS » |
| `TMDB_API_KEY` | pour l'onglet Découvrir | Clé d'API v3 (32 caractères hexadécimaux) ou jeton d'accès v4 (`eyJ…`) de TMDB |
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
existant ; la clé TMDB se colle dans une saisie masquée, clé v3 ou jeton v4),
puis crée sur GitHub le secret `CLOUDFLARE_API_TOKEN` et les variables
`CLOUDFLARE_ACCOUNT_ID` et `HEYWATCHLIST_API_URL`. Il affiche le code
administrateur une seule fois et le range dans
`~/HeyWatchlist-secrets/mots-de-passe.txt`. Il se relance sans risque, par
exemple pour ajouter plus tard la clé TMDB, les clients Google ou la clé Apple.
Il ne vérifie pas la clé TMDB auprès de TMDB (format seulement) : une clé
fausse se corrige dans le tableau de bord Cloudflare.

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

Les tests n'appellent ni Cloudflare, ni Apple, ni Google, ni TMDB : clés de
signature créées pour le test, fausse clé TMDB, `fetch` simulé, heure fixée.

## Déploiement

`.github/workflows/ci-backend.yml` : sur chaque PR qui touche `backend/`,
TypeScript et tests ; sur `main`, en plus, `wrangler d1 migrations apply DB
--remote` puis `wrangler deploy`. Sans `CLOUDFLARE_API_TOKEN` ou
`CLOUDFLARE_ACCOUNT_ID`, le déploiement est ignoré (avec un avertissement), les
tests tournent quand même.

L'adresse publique est `https://heywatchlist-api.<sous-domaine>.workers.dev`.
Elle n'est pas écrite dans le dépôt : la CI la lit dans la variable GitHub
`HEYWATCHLIST_API_URL` et l'inscrit dans les apps à la compilation.
