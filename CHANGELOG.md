# Journal des changements

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
numérotation [sémantique](https://semver.org/lang/fr/). La version de l'app est
`[workspace.package] version` du `Cargo.toml` racine. Les entrées les plus
récentes sont en tête.

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
