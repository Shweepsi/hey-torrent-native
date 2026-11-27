# Journal des changements

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
numérotation [sémantique](https://semver.org/lang/fr/). La version de l'app est
`[workspace.package] version` du `Cargo.toml` racine. Les entrées les plus
récentes sont en tête.

## [0.8.0] - 2026-11-27

Plus de code d'invitation : le lien de l'app sert d'invitation, et chaque
nouveau compte attend que l'administrateur valide son accès.

### Ajouté

- Worker : comptes `pending`, `active` ou `refused` (migration
  `0006_acces.sql`, les comptes existants restent actifs). Un compte en attente
  ou refusé n'a droit qu'à son profil, à la suppression de son compte et à
  l'enregistrement de son appareil ; tout le reste répond `access_pending` ou
  `access_refused`. Notification à l'administrateur à chaque nouvelle demande,
  et à la personne quand elle est acceptée. Routes `/v1/admin/members`
  (accepter, refuser, retirer l'accès) et `/v1/admin/allowed-emails` (adresses
  acceptées d'avance, par exemple le compte de démonstration pour Apple).
  `POST /v1/auth/bootstrap` pour le premier administrateur. Tests Vitest.
- Cœur : `AccessStatus` dans `UserProfile` ; derrière `admin`,
  `Account::claim_admin` et, dans `AdminSpace`, `members`, `approve_member`,
  `refuse_member`, `allowed_emails`, `allow_email`, `remove_allowed_email`.
  Tests `wiremock`.
- Android et iPhone : écran d'attente (vérifier, se déconnecter, retirer sa
  demande), relu au retour dans l'app et à la notification d'acceptation ;
  écran « Accès non accordé ».
- APK admin : onglet Accès (demandes à accepter ou refuser d'un geste,
  comptes dont on peut retirer l'accès, adresses acceptées d'avance) et code
  administrateur sur l'écran d'attente.

### Retiré

- Codes d'invitation : écran de saisie, table `invitations`, routes
  `/v1/invitations` et `/v1/admin/invitations`, erreurs `InvitationRequired` et
  `InvitationInvalid` du cœur.

## [0.6.1] - 2026-11-27

### Corrigé

- Android : « Se connecter avec Google » ne reste plus muet après le choix du
  compte. Le refus des services Google (code 16, app non déclarée chez Google)
  affiche « La connexion avec Google n’est pas disponible pour le moment », et
  une fenêtre fermée affiche « Connexion annulée ».
- Android : l'app ne plante plus juste après la connexion. La lecture de la
  version du cœur, pour les Réglages, s'appelait elle-même sans fin
  (`StackOverflowError`).

### Ajouté

- CI Android : les empreintes SHA-1 et SHA-256 du certificat de signature des
  APK admin et public s'affichent dans le résumé du job de release, pour les
  déclarer dans la console Google.
- Android : après un plantage, l'app affiche au lancement suivant le rapport
  (pile de l'exception, ou raison d'arrêt donnée par Android pour un plantage
  natif ou une app figée), à copier. L'app reste en pause tant qu'il est
  affiché. Rien n'est envoyé.
- Android : Firebase Crashlytics (BoM 35.2.0 : `firebase-crashlytics` 21.1.0,
  plugin 3.0.8) dans les deux APK. Les plantages remontent dans la console
  Firebase ; la CI envoie la table R8 pour des piles lisibles. Le plugin
  Google Services (4.6.0) est appliqué quand les réglages Firebase existent.

### Modifié

- Android : une erreur de Firebase en demandant le jeton des notifications ne
  fait plus planter l'app ; les notifications sont simplement coupées.

## [0.6.0] - 2026-11-26

Jalon 6 : notifications « Disponible sur Plex ». Quand un film ou un épisode
demandé arrive sur Plex, chaque personne qui l'attend reçoit une notification
sur iPhone (APNs) ou Android (FCM).

### Ajouté

- Worker : webhooks `POST /v1/hooks/plex/<jeton>` (Plex Pass, `library.new`)
  et `POST /v1/hooks/tautulli/<jeton>` (secours), URL secrète créée et
  régénérée par l'administrateur (`/v1/notifications/webhook`), dédoublonnage
  entre les deux sources, épisodes d'une même saison regroupés sur 6 heures,
  série retrouvée par TMDB (`find`) depuis les identifiants d'un épisode.
  Passage de la demande en `available`, table `library`, push aux personnes
  qui attendent ce titre (et cette saison). Migration `0004_notifications.sql`
  (`devices`, `library`, `notified`, `webhook_tokens`). Tests Vitest.
- Worker : envoi APNs (jeton ES256) et FCM HTTP v1 (compte de service), jetons
  d'appareil enregistrés par session (`PUT`/`DELETE /v1/devices`), jetons
  périmés oubliés, push d'essai (`POST /v1/notifications/test`). Nouveaux
  secrets `APNS_KEY_ID` et `APNS_PRIVATE_KEY`, posés par l'étape 7/7 de
  `configurer-backend.mjs`.
- Cœur : `register_push_device`, `unregister_push_device`, `send_test_push` ;
  `webhook_status` et `regenerate_webhook` derrière `admin`. Tests `wiremock`.
- Android : Firebase Messaging, initialisé sans le plugin Google Services
  (valeurs de `google-services.json` lues à la compilation, secret
  `GOOGLE_SERVICES_JSON`) ; permission demandée après la connexion, canal
  « Disponible sur Plex », carte Notifications dans Réglages (notification
  d'essai). APK admin : carte « Notifications Plex » avec les URL à copier.
- iPhone : APNs, permission demandée après la connexion, notifications
  affichées app ouverte, carte Notifications dans Réglages. La CI ajoute la
  capacité « Notifications push » à l'identifiant d'app.

## [0.5.0] - 2026-11-26

Jalon 5 : l'espace admin, dans l'APK admin seulement. L'administrateur voit
toutes les demandes, cherche dans ses sources Torznab (Prowlarr), choisit une
release notée et l'envoie à son qBittorrent ; il peut refuser avec un motif,
suivre les téléchargements, régler ses serveurs et gérer les invitations.

### Ajouté

- Worker : routes `/v1/admin/requests` (liste, `start`, `refuse`, `reopen`),
  `/v1/admin/settings` (réglages chiffrés, AES-256-GCM) et
  `/v1/admin/invitations` (liste, révocation) ; erreur `conflict` (409) ;
  migration `0005_admin.sql`. Tests Vitest.
- Cœur, fonctionnalité `admin` : objet `AdminSpace`, recherche Torznab en
  parallèle (6 s par source, repli sur la recherche simple, résultats au fil
  de l'eau), lecture des noms de release, tri des releases francophones,
  client qBittorrent v4 et v5. Tests `wiremock` et jeu de cas de tri.
- Android, édition admin : `AdminActivity` (Demandes, fiche, Suivi, Serveurs,
  Invitations), tests des ViewModels dans `src/testAdmin/`, lancés par la CI.
- `configurer-backend.mjs` pose le secret `CREDENTIALS_KEY`.

### Modifié

- Android : la création d'invitations passe de Réglages à l'espace admin ;
  `KeystoreSessionStore` accepte un fichier et une clé propres ;
  `HeyTextField` peut masquer sa saisie.

## [0.4.0] - 2026-11-26

Jalon 4 : la watchlist. Depuis la fiche d'un film ou d'une série, on l'ajoute
à sa watchlist, ce qui crée la demande ou rattache la personne à celle qui
existe déjà. L'onglet Watchlist montre l'état de chaque demande (demandée, en
cours, disponible, refusée). Une série se demande entière ou par saisons.

### Ajouté

- Worker : routes `GET /v1/watchlist`, `PUT` et `DELETE
  /v1/watchlist/{movie|tv}/{id}`, tables `requests` et `watchlist_items`
  (migration `0003_watchlist.sql`), une demande par titre, titre et affiche lus
  chez TMDB par le Worker, saisons vérifiées, suppression des demandes que plus
  personne ne suit. Tests Vitest.
- Cœur : objet `Watchlist` (liste, ajout, retrait, rafraîchissement), copie
  locale SQLite et file d'attente hors ligne d'un changement par titre, envoyée
  au retour du réseau ; erreurs typées `WatchlistError`. Tests `wiremock`.
- Android et iPhone : bouton « Ajouter à ma watchlist » sur la fiche, choix
  « série entière » ou saisons, état de la demande, retrait ; onglet Watchlist
  avec tirer pour rafraîchir et bandeau hors ligne.

### Modifié

- Cœur : `ApiFailure::Rejected` pour les refus `invalid_request` et
  `forbidden` du Worker.
- Worker : le relais TMDB expose sa lecture en cache (`cachedTmdb`) pour la
  watchlist.

## [0.3.0] - 2026-11-26

Jalon 3 : Découvrir. L'onglet Découvrir parcourt le catalogue TMDB en
français sur iPhone et Android : carrousel, rangées, genres, recherche
instantanée et fiche détail. Aucun badge « Sur Plex » ni ajout à la watchlist
pour l'instant (jalons 4 et 6).

### Ajouté

- Worker : relais `GET /v1/tmdb/<chemin>` du catalogue TMDB, derrière le jeton
  d'accès, sur une liste blanche stricte de chemins et de paramètres (langue
  `fr-FR`, région `FR` imposées), avec cache dans D1 (table `tmdb_cache`,
  migration `0002_tmdb_cache.sql` ; la Cache API ne fonctionne pas sur
  `workers.dev`). Nouveau secret `TMDB_API_KEY` (clé v3 ou jeton v4), saisi par
  `configurer-backend.mjs`. Tests Vitest.
- Cœur : objet `Catalog` (étagères, genres, genre, recherche, fiche, saison),
  types sans texte affiché, erreurs typées `CatalogError`, décodage tolérant
  (une entrée illisible est ignorée), URL d'images fabriquées par le cœur,
  cache SQLite hors ligne (`rusqlite`, SQLite embarqué) avec repli sur
  l'entrée périmée quand le réseau manque, correspondance des genres de films
  vers ceux des séries. Tests `wiremock`.
- Android : écrans Découvrir (carrousel, rangées indépendantes avec squelettes
  et « Réessayer », pastilles de genres), Genre, recherche instantanée, fiche
  détail plein écran (bande-annonce, plateformes en France, casting, saisons et
  épisodes, similaires) ; images par Coil 3.3.0.
- iPhone : mêmes écrans en Liquid Glass, recherche dans la bulle séparée
  (`Tab(role: .search)`), fiche avec transition zoom ; images par
  `AsyncImage`.
- Mention « Disponibilités fournies par JustWatch » dans la fiche, comme TMDB
  l'exige pour les plateformes.

### Modifié

- La session expirée détectée par le catalogue ramène à l'accueil, comme celle
  détectée par le compte.
- `PrivacyInfo.xcprivacy` : déclare `FileTimestamp` (`C617.1`) et `DiskSpace`
  (`E174.1`, provisoire) à cause de SQLite embarqué.

## [0.2.0] - 2026-11-25

Jalon 2 : comptes et connexion. On se connecte avec Apple (iPhone) ou Google
(Android et iPhone), sur invitation, et Réglages affiche son prénom.

### Ajouté

- `backend/` : Worker Cloudflare `heywatchlist-api` en TypeScript, base D1
  `heywatchlist` (tables des comptes seulement). Vérification des jetons
  d'identité Apple et Google (signature, émetteur, audience, nonce à usage
  unique), comptes sur invitation, premier compte administrateur par code
  d'amorçage, même compte pour la même adresse e-mail vérifiée, jeton d'accès
  de 15 minutes et jeton de rafraîchissement renouvelé (réutilisation
  détectée), création d'invitations par l'administrateur, suppression du
  compte avec révocation chez Apple. 35 tests Vitest dans `workerd`.
- CI `ci-backend.yml` : TypeScript et tests sur chaque PR ; sur `main`,
  migrations D1 et déploiement si les réglages Cloudflare existent.
- `backend/scripts/configurer-backend.mjs` : réglage en une commande sur le PC
  du propriétaire (jeton Cloudflare, secrets du Worker, secret et variables
  GitHub), sans afficher de secret.
- Cœur : objet `Account` (connexion, profil, rafraîchissement automatique et
  sérialisé des jetons, déconnexion, suppression), session gardée par l'app
  (Keychain, Keystore) via `SessionStore`, jeton d'accès en mémoire seulement ;
  connexion Google sur iPhone par OAuth avec PKCE ; client HTTPS `reqwest` +
  `rustls` ; `create_invitation` dans l'édition admin. Tests `wiremock`.
- Android : accueil avec Google (Credential Manager), saisie du code
  d'invitation, onglets Découvrir · Watchlist · Réglages, Réglages avec prénom,
  déconnexion et suppression du compte ; carte « Inviter quelqu'un » dans
  l'APK admin.
- iPhone : accueil avec « Se connecter avec Apple » et Google, mêmes écrans ;
  suppression d'un compte Apple précédée d'une nouvelle autorisation Apple.
- CI iOS : capacité « Sign in with Apple » ajoutée à l'identifiant d'app et
  profil régénéré si besoin ; adresse du serveur lue dans la variable GitHub
  `HEYWATCHLIST_API_URL`, comme sur Android.

### Retiré

- L'écran « pong » du jalon 1 et la carte d'état de l'édition admin.

## [0.1.1] - 2026-11-25

### Modifié

- CI iOS : compilation avec Xcode 27 et le SDK iOS 27 (image `xcode-27`), pour
  lever l'avertissement Apple ITMS-90725 (SDK iOS 27 exigé à partir d'avril
  2027). Le simulateur des tests est choisi parmi ceux de la machine au lieu
  d'un nom figé. La cible de déploiement reste iOS 26.

## [0.1.0] - 2026-11-25

Jalon 1 : fondations et chaîne de compilation. Une app vide mais installable,
qui n'affiche que ce que renvoie le cœur Rust. Aucune fonctionnalité utilisateur
(ni TMDB, ni connexion, ni backend).

### Ajouté

- Espace de travail Rust (`Cargo.toml`) : version de l'app en source unique,
  lints clippy `unwrap_used`, `expect_used` et `panic` refusés, `unsafe`
  interdit, profil de release réglé pour le mobile (sans `panic = "abort"`).
- Cœur `heywatchlist-core` exposé par UniFFI (macros, sans fichier UDL) :
  `core_version()`, `ping()` (« pong »), `ping_async()` sur `tokio`,
  `has_admin()` et l'énumération d'erreurs `CoreError`.
- Fonctionnalité Cargo `admin` (désactivée par défaut) : ajoute
  `admin_ping()` (« admin-pong »). Seule l'édition admin d'Android la compile.
- Outil `tools/uniffi-bindgen` et scripts `scripts/generate-bindings.sh`,
  `scripts/build-android-core.sh` (`cargo-ndk`, `arm64-v8a` et `x86_64`, API 31)
  et `scripts/build-ios-xcframework.sh`. Les liaisons générées ne sont pas
  versionnées.
- App Android (Jetpack Compose, Android 12 minimum) : éditions `public`
  (`HeyWatchlist`) et `admin` (`HeyWatchlist Admin`), identifiants
  `io.github.shweepsi.heywatchlist` et `.admin`, premier `HeyTheme` en
  verre dépoli (Haze, sans Material 3), écran d'accueil qui affiche le nom, la
  version du cœur et « pong ». Le code admin est confiné à `src/admin/`.
- App iPhone (SwiftUI, iOS 26 minimum) : projet décrit par XcodeGen
  (`ios/project.yml`, identifiant `io.github.shweepsi.heywatchlist`), Liquid
  Glass, même écran d'accueil.
- Textes en français uniquement, dans `strings.xml` et `Localizable.xcstrings`.
- Chaîne d'intégration continue GitHub Actions (voir `docs/CI.md`).
- Documentation : contexte du projet, plan par jalons, décisions, identité
  visuelle, reste à faire, règles pour les agents de code et guide de
  contribution.
