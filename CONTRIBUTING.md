# Contribuer à HeyWatchlist

Dépôt privé. Ce guide vaut pour les personnes comme pour les agents de code
(voir aussi `CLAUDE.md`). Lire d'abord `context_hey-torrent.md`, puis
`docs/DECISIONS.md`, `docs/PLAN.md` et, avant toute interface, `docs/DESIGN.md`.

## Principes

- **Un seul périmètre à la fois** : le jalon en cours dans `docs/PLAN.md`. Une
  option « ouverte » ou « par défaut, à confirmer » ne se code pas avant le
  choix du propriétaire.
- **Toute la logique dans `core/`** (Rust). Swift et Kotlin affichent, traduisent
  les erreurs du cœur en français et gardent les secrets dans le Keychain ou le
  Keystore.
- Le cœur est **tolérant** (une entrée illisible est ignorée) et **ne plante
  jamais volontairement** : ni `unwrap`, `expect` ni `panic!` hors des tests.

## Environnement

| Pour | Il faut |
|---|---|
| Le cœur | Rust stable (installé par `rust-toolchain.toml`, avec `rustfmt` et `clippy`) |
| Android | JDK 17 ou plus, SDK Android (plateforme 36), NDK et `cargo-ndk` pour les `.so` ; voir `android/README.md` |
| iPhone | Rien en local : GitHub Actions compile tout (pas de Mac) ; voir `ios/README.md` et `docs/CI.md` |

## Flux de travail

1. Une **branche par sujet**, depuis `main`. Pas de push direct sur `main`.
2. Des commits en **Conventional Commits**, avec une portée si utile :
   `feat(core): …`, `fix(android): …`, `refactor`, `test`, `docs`, `chore`,
   `ci`.
3. Une **pull request** : la CI doit être au vert avant la fusion.
4. Mettre à jour `docs/TODO.md` et `docs/PLAN.md` quand une ligne est faite et
   vérifiée, jamais avant.

## Vérifications avant de livrer

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
cargo clippy --all-targets --features admin -- -D warnings
cargo test --features admin
```

Android, depuis `android/` :

```sh
./gradlew testPublicDebugUnitTest assembleAdminDebug
```

iOS n'a pas de vérification locale : le job macOS de la CI compile et teste.

## Version et journal des changements

La version de l'app a **une seule source** : `[workspace.package] version` du
`Cargo.toml` racine. Android (`versionName`, `versionCode`) et iOS
(`MARKETING_VERSION`) la lisent ; ne la copiez nulle part.

Toute pull request qui change l'app :

1. monte la version (numérotation sémantique : correctif, fonctionnalité ou
   rupture) dans `Cargo.toml` ;
2. lance `cargo check` pour mettre à jour `Cargo.lock`, et commite les deux ;
3. ajoute son entrée **en tête** de `CHANGELOG.md` (format *Keep a Changelog*,
   en français : « Ajouté », « Modifié », « Corrigé », « Supprimé »).

Une pull request qui ne touche que la documentation ou la CI ne monte pas la
version.

## Conventions de code

- **Rust** : édition 2024, `cargo fmt`, clippy sans avertissement. Erreurs
  typées avec `thiserror`, exposées à UniFFI en énumérations ; le cœur ne
  produit aucun texte affiché (ses messages d'erreur ne servent qu'aux
  journaux). Opérations longues en `async` sur `tokio`. Jamais `panic = "abort"`.
- **Kotlin** (Compose) : `ktlint` (configuration dans `android/.editorconfig`),
  aucun appel au cœur sur le fil principal, composants maison sur Compose
  Foundation, **sans Material 3**.
- **Swift** (SwiftUI) : `swift-format`, Human Interface Guidelines, appels au
  cœur hors du fil principal (`Task`), Liquid Glass du système.
- **Scripts** : bash, `set -euo pipefail`, compatibles `shellcheck`,
  commentaires en français, bit d'exécution positionné.
- **Langue** : identifiants en anglais ; commentaires et documentation en
  français, concis ; textes affichés en français **dans les fichiers de
  ressources** (`strings.xml`, `Localizable.xcstrings`).
- Pas de code mort ni de `TODO` laissé dans le code : le travail restant va
  dans `docs/TODO.md`.

## Tests

- Toute fonctionnalité du cœur arrive avec ses tests.
- **Les tests n'appellent jamais les vrais services** (TMDB, Plex, qBittorrent,
  sources Torznab, backend) : réponses simulées avec `wiremock`, y compris les
  cas d'échec (délai dépassé, 401, JSON invalide, réponse partielle). Des
  réponses réelles peuvent servir de modèle une fois anonymisées.
- Le scoring des releases est couvert par des noms de releases réels dans
  `core/tests/fixtures/`.
- Les interfaces testent au minimum leurs modèles d'écran (ViewModel).
- `unwrap` et `expect` sont permis dans les tests seulement.

## Sécurité et confidentialité

- Aucun secret, clé, jeton, identifiant ni vraie URL de serveur dans le dépôt,
  les tests, les journaux ou les rapports de plantage. Les secrets de CI passent
  par les secrets GitHub ; `google-services.json` et les clés de signature ne
  sont jamais commités.
- Ne jamais journaliser d'identifiant, de jeton, de lien magnet ni d'URL
  contenant une clé.
- **Aucun tracker nommé ni préconfiguré**, nulle part.
- Le code d'administration n'existe que sous `android/app/src/admin/` et
  derrière la fonctionnalité Cargo `admin`. Un fichier de l'édition publique ne
  doit référencer aucun symbole admin.

## Ajouter une dépendance

Préférer une version largement utilisée et **vérifier qu'elle existe** (crates.io,
Maven Central, plugin Gradle) avant de l'écrire. Les versions Android vivent dans
`android/gradle/libs.versions.toml`, jamais dans `app/build.gradle.kts`. Pas de
dépendance à `material3`. Dire dans la pull request ce qui n'a pas pu être
vérifié (par exemple une version que seule la CI compile).
