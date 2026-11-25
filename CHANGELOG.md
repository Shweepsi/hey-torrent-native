# Journal des changements

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
numérotation [sémantique](https://semver.org/lang/fr/). La version de l'app est
`[workspace.package] version` du `Cargo.toml` racine. Les entrées les plus
récentes sont en tête.

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
