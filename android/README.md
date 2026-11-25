# HeyWatchlist pour Android

Projet Gradle (Kotlin DSL) : un module `:app`, Jetpack Compose sans Material 3,
cœur Rust relié par UniFFI. Le Jalon 1 n'affiche que ce que renvoie le cœur :
son nom, sa version et la réponse « pong ».

## Prérequis

- JDK 17 ou plus.
- SDK Android avec la plateforme 36 (`compileSdk`/`targetSdk` = 36).
- Rust (`rust-toolchain.toml` à la racine) et les cibles Android :
  `rustup target add aarch64-linux-android x86_64-linux-android`.
- Pour compiler les `.so` : le NDK (r23 ou plus) dans `ANDROID_NDK_HOME`, et
  `cargo install cargo-ndk` (version 4 ou plus).

Les tests JVM n'exigent ni NDK ni cargo-ndk : seule la génération des liaisons
Kotlin demande Rust (compilation du cœur pour l'hôte).

## Commandes

```sh
cd android
./gradlew testPublicDebugUnitTest assembleAdminDebug   # commande de la CI
./gradlew :app:testPublicDebugUnitTest :app:testAdminDebugUnitTest :buildSrc:test  # tous les tests JVM
./gradlew assemblePublicRelease assembleAdminRelease   # APK de release
./gradlew bundlePublicRelease                          # AAB pour le Play Store
```

## Éditions

| | `public` | `admin` |
|---|---|---|
| Identifiant | `io.github.shweepsi.heywatchlist` | `io.github.shweepsi.heywatchlist.admin` |
| Nom affiché | HeyWatchlist | HeyWatchlist Admin |
| Cœur Rust | sans la fonctionnalité Cargo `admin` | avec `admin` |
| Code propre | `src/public/` | `src/admin/` |

`src/main/` ne référence aucun symbole admin. L'écran d'accueil appelle
`EditionContent.Footer()`, objet défini dans `src/public/` (vide) et dans
`src/admin/` (carte `AdminStatus`). Les textes admin ne sont que dans
`src/admin/res/`.

## Version et signature

- `versionName` est lue dans `[workspace.package]` du `Cargo.toml` racine ;
  `versionCode` = `major * 10000 + minor * 100 + patch` (minor et patch < 100).
  Rien n'est dupliqué dans Gradle.
- La release est signée avec les variables d'environnement
  `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` et
  `ANDROID_KEY_PASSWORD` quand les quatre sont définies, sinon avec la clé de
  debug (poste de développement seulement).

## Cœur Rust

Gradle lance `scripts/build-android-core.sh` à travers deux tâches par variante :

- `generateUniffiBindings<Variante>` : compile le cœur pour l'hôte avec les
  mêmes fonctionnalités que l'app, puis génère les liaisons Kotlin
  (`uniffi.heywatchlist_core`) avec `scripts/generate-bindings.sh` ;
- `buildRustCore<Variante>` : `cargo ndk` pour `arm64-v8a` et `x86_64`
  (API 31).

Leurs sorties sont déclarées à AGP (`addGeneratedSourceDirectory`) : le Kotlin
généré est compilé avec l'app, les `.so` sont empaquetés. AGP les place sous
`app/build/generated/`. Rien de tout cela n'est versionné. À la main, le script
écrit par défaut sous `app/build/rustJniLibs/<édition>/<type>/<abi>/` et
`app/build/generated/uniffi/<édition>/kotlin` (voir `--help`).

## Interface

- `HeyTheme` : jetons de couleur sombres d'abord (`#4f9cf7`, `#0c0c0f`, verre
  `rgba(255,255,255,0.09)` avec filet de 1 px à 0,2), typographie, formes.
  Le thème suit le réglage clair/sombre du système.
- `GlassSurface` : flou par Haze. Sans `HazeState` fourni, ou quand Haze coupe
  le flou, la surface devient translucide et plus opaque, sans flou.
- Aucun appel au cœur sur le fil principal : `HomeViewModel` passe par
  `CoreGateway` (`UniffiCoreGateway` est le seul fichier qui importe le code
  généré), ce qui permet de tester l'écran sans bibliothèque native.
- Les textes sont dans `res/values/strings.xml` (français uniquement).

## Règles R8

`app/proguard-rules.pro` garde JNA et `uniffi.**` : le code généré charge la
bibliothèque native par réflexion.
