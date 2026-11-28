# Backend : Worker Cloudflare `heywatchlist-api`

API de HeyWatchlist (comptes, relais du catalogue TMDB), en TypeScript, sur
Cloudflare Workers avec une base D1. Les apps ne lui parlent que par le cœur Rust (`core/src/api.rs`).
Le nom `heywatchlist-api` est fixé : le Worker porte déjà le secret
`FCM_SERVICE_ACCOUNT` (jalon 6), un autre nom le perdrait.

## Ce qu'il fait (jalon 2)

- **Connexion Apple ou Google, accès validé.** L'app envoie le jeton
  d'identité obtenu du système ; le Worker vérifie sa signature (clés publiques
  d'Apple et de Google), l'émetteur, l'audience, l'expiration et le nonce (un
  nonce ne sert qu'une fois). Une personne inconnue obtient un compte **en
  attente** (`status: "pending"`) et l'administrateur reçoit une notification ;
  la même adresse e-mail **vérifiée** retrouve le même compte, qu'elle vienne
  d'Apple ou de Google.
- **Accès** : un compte `pending` ou `refused` n'a droit qu'à son profil
  (`GET /v1/me`), à la suppression de son compte et à l'enregistrement de son
  appareil (pour être prévenu de l'acceptation). Tout le reste répond `403
  access_pending` ou `access_refused`, l'état étant relu dans D1 à chaque
  appel. Une adresse acceptée d'avance (`allowed_emails`) donne un compte
  actif dès sa création.
- **Premier administrateur** : un compte connecté envoie le code du secret
  `BOOTSTRAP_CODE` à `POST /v1/auth/bootstrap` tant qu'aucun administrateur
  n'existe (écran d'attente de l'APK admin). Ensuite, ce code ne sert plus à
  rien.
- **Sessions** : jeton d'accès de 15 minutes (JWT HS256) et jeton de
  rafraîchissement renouvelé à chaque usage. Réutiliser un ancien jeton de
  rafraîchissement (vol probable) ferme la session. Une session dure 90 jours
  sans usage.
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

## Ce qu'il fait en plus (jalon 6)

- **Notifications « Disponible sur Plex »** : Plex (webhook Plex Pass,
  `library.new`) et Tautulli (« Recently Added », en secours) appellent une URL
  secrète du Worker à chaque ajout. Le Worker reconnaît le média en
  identifiants TMDB, le note dans `library`, passe la demande en `available`
  et envoie une push à chaque personne qui l'attend : APNs pour l'iPhone, FCM
  pour Android. Voir « Notifications » plus bas.

## Ce qu'il fait en plus (recherche automatique, 2026-11-28)

- **Claude** : `POST /v1/admin/recommend` envoie à l'API Anthropic la demande,
  les règles de l'administrateur et les releases trouvées (noms, tailles,
  sources ; jamais les liens), avec sa clé d'API gardée chiffrée dans ses
  réglages. Réponse : `{release_id, reason}` (`release_id` nul si aucune ne
  convient). Erreurs : `assistant_not_configured`, `assistant_auth_failed`,
  `assistant_unavailable`.
- **Déclencheur horaire** (`[triggers]` de `wrangler.toml`,
  `src/autosearch.ts`) : recherche automatique des demandes en attente (au
  plus toutes les 6 h chacune, 8 par passage), alerte à l'administrateur après
  3 semaines sans release convenable, lecture de la bibliothèque Plex toutes
  les 6 h si une connexion est réglée. État : `GET /v1/admin/automation`.
- **Plex** : `POST /v1/admin/plex/test` `{url, token}` vérifie la connexion
  depuis le Worker (`plex_unreachable`, `plex_auth_failed`). Le jeton part en
  en-tête `X-Plex-Token`, jamais dans l'URL.

La clé Anthropic et le jeton Plex ne sont pas des secrets du Worker : ils
font partie des réglages chiffrés de l'administrateur.

## Routes

| Méthode et chemin | Accès | Rôle |
|---|---|---|
| `GET /v1/health` | libre | `{"status":"ok"}` |
| `GET /confidentialite` | libre | Politique de confidentialité (HTML), liée depuis les Réglages et les fiches des stores |
| `GET /suppression-du-compte` | libre | Comment supprimer son compte (HTML), demandé par Google Play |
| `GET /v1/config` | libre | Identifiants publics des clients Google (`google_web_client_id`, `google_ios_client_id`, ou `null`) |
| `GET /v1/posters` | libre | Chemins des affiches en tendance de la semaine (`{"posters": ["/abc.jpg", …]}`, 24 au plus), pour le décor de l’écran de connexion ; même cache d’une heure que le relais TMDB |
| `POST /v1/auth/sign-in` | libre | `{provider, id_token, nonce, given_name?}` → jetons et profil (nouveau compte : `status: "pending"`) |
| `POST /v1/auth/bootstrap` | jeton d'accès, tout état | `{code}` : premier administrateur, tant qu'il n'y en a aucun → `{user}` ; sinon `403 forbidden` |
| `POST /v1/auth/refresh` | jeton de rafraîchissement | Nouveaux jetons |
| `POST /v1/auth/sign-out` | jeton de rafraîchissement | Ferme la session (204) |
| `GET /v1/me` | jeton d'accès, tout état | Profil : `id`, `role`, `display_name`, `status` (`active`, `pending`, `refused`), `providers` |
| `DELETE /v1/me` | jeton d'accès, tout état | Supprime le compte ; `{apple_authorization_code?}` |
| `GET /v1/watchlist` | jeton d'accès | `{items}` : ma watchlist, la plus récente d'abord, avec l'état de chaque demande |
| `PUT /v1/watchlist/{movie\|tv}/{id}` | jeton d'accès | Ajoute ou met à jour un titre ; `{seasons?}` → `{item}` |
| `DELETE /v1/watchlist/{movie\|tv}/{id}` | jeton d'accès | Retire un titre (204) |
| `PUT /v1/devices` | jeton d'accès, tout état | `{platform: "ios"\|"android", token}` : jeton push de cet appareil, rattaché à la session (204) |
| `DELETE /v1/devices` | jeton d'accès, tout état | Oublie l'appareil de cette session (204) |
| `POST /v1/notifications/test` | jeton d'accès | Push d'essai vers mes appareils → `{devices, sent}` |
| `GET /v1/notifications/webhook` | administrateur | `{configured, created_at, last_used_at}`, jamais l'URL |
| `POST /v1/notifications/webhook` | administrateur | Nouvelle URL, l'ancienne est révoquée (201, `{plex_url, tautulli_url, tautulli_body}`, montrées une seule fois) |
| `POST /v1/hooks/{plex\|tautulli}/<jeton>` | jeton de l'URL | Nouvel ajout sur Plex → `{status}` : `notified`, `grouped`, `duplicate`, `no_request`, `ignored` ou `unknown_media`. Jeton inconnu : `404 not_found` |
| `GET /v1/tmdb/<chemin>` | jeton d'accès | Relais du catalogue TMDB : liste blanche de chemins et de paramètres (voir « Relais TMDB »), réponse TMDB telle quelle |
| `GET /v1/admin/requests?status=open\|all\|requested\|in_progress\|available\|refused` | administrateur | Demandes de tous les comptes, avec leurs abonnés et saisons (`open` par défaut : demandées puis en cours, les plus anciennes d'abord) |
| `POST /v1/admin/requests/<id>/start` | administrateur | `{release_title, torrent_hash?}` : passe la demande « en cours » |
| `POST /v1/admin/requests/<id>/refuse` | administrateur | `{reason?}` (200 caractères au plus) : refuse la demande |
| `POST /v1/admin/requests/<id>/reopen` | administrateur | Remet une demande en cours ou refusée « demandée » |
| `GET`, `PUT`, `DELETE /v1/admin/settings` | administrateur | Réglages des serveurs de l'administrateur, `{settings}` (texte opaque produit par le cœur), chiffrés avec `CREDENTIALS_KEY` |
| `GET /v1/admin/members` | administrateur | `{members}` : comptes avec `display_name`, `email` vérifiée, `status`, `role`, dates ; ceux en attente d'abord |
| `POST /v1/admin/members/<id>/approve` | administrateur | Accepte le compte (ou rend l'accès) et lui envoie une notification (204) ; un administrateur : `409 conflict` |
| `POST /v1/admin/members/<id>/refuse` | administrateur | Refuse le compte ou retire son accès (204) |
| `GET /v1/admin/allowed-emails` | administrateur | `{emails}` : adresses acceptées d'avance |
| `POST /v1/admin/allowed-emails` | administrateur | `{email}` : accepte d'avance cette adresse, et tout de suite les comptes en attente qui l'ont (201, `{email, approved}`) |
| `DELETE /v1/admin/allowed-emails` | administrateur | `{email}` : oublie l'adresse ; les comptes déjà créés gardent leur accès (204) |

Les erreurs sont `{"error": "<code>"}`, avec un code stable que le cœur traduit
en type d'erreur : `invalid_request` (400), `invalid_token`,
`unauthorized`, `session_expired` (401), `access_pending`,
`access_refused`, `forbidden` (403), `not_found` (404), `conflict` (409,
la demande a changé entre-temps),
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
| `find/{id}` | `external_source` (`imdb_id` ou `tvdb_id`, obligatoire) | 7 j |

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

## Notifications

- **Sources.** Plex envoie un formulaire dont le champ `payload` est du JSON ;
  seul `event = library.new` compte. Tautulli envoie le corps JSON donné par
  l'APK admin (`tautulli_body`), avec `event = recently_added`. Tout autre
  événement, ou une charge illisible, est ignoré (200).
- **Identifiants.** Film : `tmdb://` (agents Plex récents), sinon IMDb par
  `find`. Épisode Plex : ses `tmdb://`, `tvdb://`, `imdb://` sont ceux de
  l'**épisode** ; la série est retrouvée par `find` (TVDB, puis IMDb), saison
  et épisode par `parentIndex` et `index`. Ancien format
  `com.plexapp.agents.<agent>://…` et Tautulli : identifiants du film ou de la
  série.
- **Dédoublonnage** (table `notified`) : une clé par arrivée (`movie:603`,
  `tv:95396:s2e1`…). Plex puis Tautulli pour le même ajout : une seule
  push. Les épisodes d'une même saison arrivés à moins de 6 heures d'écart ne
  font qu'une push (`grouped`).
- **Destinataires** : les personnes dont la watchlist contient le titre (et,
  pour un épisode, la saison, ou la série entière), sur les appareils dont la
  session est encore ouverte. Un jeton refusé comme périmé par Apple ou Google
  est oublié.
- **Texte** : le titre du média, tel que Plex ou Tautulli le donne, et une clé
  de traduction (`push_available_movie`, `push_available_show`,
  `push_available_season`, `push_available_episode`, `push_test`) traduite par
  l'app. Aucun jeton d'appareil, URL ni clé n'est journalisé.
- **URL secrète** : son jeton est dans le chemin (Plex ne sait pas ajouter
  d'en-tête) ; seul son condensé est gardé (`webhook_tokens`). Le journal des
  requêtes de Cloudflare peut voir passer le chemin : régénérer l'URL depuis
  l'APK admin la révoque.

## Base D1

Base `heywatchlist` (identifiant dans `wrangler.toml`), migrations dans
`migrations/`. Le jalon 2 crée les tables des comptes : `users`, `identities`,
`invitations`, `sessions`, `used_nonces`. Le jalon 3 ajoute `tmdb_cache`
(`0002_tmdb_cache.sql`), des données publiques sans lien avec `users`. Le
jalon 4 ajoute `requests` (une demande par titre, son état) et
`watchlist_items` (rattachées à `users` et à `requests`,
`0003_watchlist.sql`). Le jalon 5 (`0005_admin.sql`) ajoute aux demandes la
release envoyée et crée `credentials`, les réglages chiffrés de
l'administrateur. Le jalon 6 (`0004_notifications.sql`) ajoute `devices`
(rattachés à `users` et à `sessions`), `library`, `notified` et
`webhook_tokens`. La validation des comptes (`0006_acces.sql`) ajoute à
`users` leur état d'accès (`status`, les comptes existants restent actifs),
crée `allowed_emails` et supprime `invitations`. Les tables des jalons suivants arriveront avec leur jalon,
dans de nouvelles migrations, rattachées à `users` par `ON DELETE CASCADE`.

## Secrets du Worker

Tous posés par `scripts/configurer-backend.mjs` (voir plus bas), jamais dans
le dépôt.

| Nom | Obligatoire | Contenu |
|---|---|---|
| `SESSION_SIGNING_KEY` | oui | 32 octets aléatoires en base64 : signe les jetons d'accès. Le changer déconnecte tout le monde. |
| `BOOTSTRAP_CODE` | jusqu'au premier compte | Code qui crée le compte administrateur |
| `GOOGLE_WEB_CLIENT_ID` | pour Google sur Android | Client OAuth « Application Web » |
| `GOOGLE_IOS_CLIENT_ID` | pour Google sur iPhone | Client OAuth « iOS » |
| `CREDENTIALS_KEY` | pour l'espace admin | 32 octets aléatoires en base64 : chiffre (AES-256-GCM) les réglages des serveurs de l'administrateur. Ne jamais la changer : les réglages enregistrés deviendraient illisibles (il faudrait les ressaisir). |
| `TMDB_API_KEY` | pour l'onglet Découvrir | Clé d'API v3 (32 caractères hexadécimaux) ou jeton d'accès v4 (`eyJ…`) de TMDB |
| `APPLE_TEAM_ID`, `APPLE_SIGNIN_KEY_ID`, `APPLE_SIGNIN_PRIVATE_KEY` | avant la publication | Clé « Sign in with Apple » : révocation à la suppression d'un compte |
| `FCM_SERVICE_ACCOUNT` | pour les push Android | Compte de service Google (JSON) limité à FCM ; déjà posé |
| `APNS_KEY_ID`, `APNS_PRIVATE_KEY` (et `APPLE_TEAM_ID`) | pour les push iPhone | Clé « Apple Push Notifications service » (`.p8`) |
| `PRIVACY_CONTACT` | avant la publication | Contact du responsable des données (e-mail ou texte), affiché par `/confidentialite` et `/suppression-du-compte` ; posé par hey-kit. Sans lui, les pages renvoient vers « l’administrateur du serveur ». |

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
exemple pour ajouter plus tard la clé TMDB, les clients Google, la clé Apple ou
la clé APNs des notifications (étape 7/7).
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
