# HeyWatchlist

**Un Overseerr natif, centré sur la découverte TMDB.** Les proches du
propriétaire d'un serveur Plex parcourent les films et séries, les ajoutent à
leur watchlist (chaque ajout est une demande), suivent leur état et sont
prévenus quand le média est disponible sur Plex. L'administrateur traite les
demandes.

Applications natives : **SwiftUI** sur iPhone (iOS 26 minimum), **Jetpack
Compose** sur Android (Android 12 minimum), avec un cœur **Rust** partagé.
Interface en français, style verre dépoli (voir `docs/DESIGN.md`).

## État du projet

**Jalon 1 en cours : fondations et chaîne de compilation.** L'app est vide
exprès : un seul écran affiche ce que renvoie le cœur Rust (« pong » et la
version du cœur). Il n'y a encore ni TMDB, ni connexion, ni backend, ni
watchlist. L'objectif est une app installable sur iPhone (TestFlight) et sur
Android (APK), entièrement compilée par GitHub Actions. Le plan complet est
dans `docs/PLAN.md`, le reste à faire dans `docs/TODO.md`.

À l'écriture de ce fichier, seul le cœur Rust a été compilé et testé ; les
builds Android et iOS n'ont pas encore tourné sur la CI.

## Architecture

```text
┌─────────────────────────────┐   ┌──────────────────────────────┐
│ iPhone : SwiftUI            │   │ Android : Jetpack Compose    │
│ Liquid Glass, iOS 26        │   │ HeyTheme + Haze, API 31      │
└──────────────┬──────────────┘   └───────────────┬──────────────┘
               │                                  │
               │ affichent seulement et           │
               │ traduisent les erreurs           │
               ▼                                  ▼
┌────────────────────────────────────────────────────────────┐
│ UniFFI : liaisons Swift et Kotlin générées par macros      │
├────────────────────────────────────────────────────────────┤
│ core/ : cœur Rust (tokio)                                  │
│ toute la logique, erreurs typées, aucun texte affiché      │
└──────────────────────────────┬─────────────────────────────┘
                               │ HTTPS (à partir du jalon 2)
                               ▼
┌────────────────────────────────────────────────────────────┐
│ Backend Cloudflare : Workers + D1 (jalon 2)                │
└────────────────────────────────────────────────────────────┘
```

- **Le cœur** (`core/`) porte toute la logique. Il ne produit aucun texte
  affiché : il renvoie des types d'erreur que chaque interface traduit en
  français.
- **UniFFI** génère les liaisons Kotlin et Swift à partir des macros
  `#[uniffi::export]`. Elles ne sont jamais versionnées.
- **Les interfaces** n'affichent que : aucune règle métier, aucun appel réseau.
- **Le backend** (jalon 2) n'existe pas encore.

### Deux apps, un seul projet

| | HeyWatchlist | HeyWatchlist Admin |
|---|---|---|
| Plateformes | iPhone, Android | Android seulement |
| Diffusion | App Store, Play Store | APK installé en privé |
| Contenu | Découvrir · Watchlist · Réglages | Idem, plus l'espace admin (jalon 5) |
| Cœur Rust | sans la fonctionnalité Cargo `admin` | avec `admin` |

Le code d'administration n'existe que dans l'édition admin
(`android/app/src/admin/` et la fonctionnalité Cargo `admin`) : l'app publique
n'en contient aucun symbole. Au jalon 1, l'édition admin n'ajoute qu'une carte
d'état : la réponse « admin-pong » du cœur et la présence du code
d'administration.

## Organisation du dépôt

```text
hey-torrent-native/
├── Cargo.toml             Espace de travail Rust ; version de l'app (source unique)
├── core/                  Cœur Rust (crate heywatchlist-core), tests
├── tools/uniffi-bindgen/  Génère les liaisons Kotlin et Swift
├── android/               Projet Gradle (Compose), éditions public et admin
├── ios/                   Interface SwiftUI ; projet Xcode généré (project.yml)
├── scripts/               Compilation du cœur, XCFramework, liaisons
├── docs/                  PLAN, DECISIONS, DESIGN, CI, TODO
├── .github/               CI GitHub Actions
├── context_hey-torrent.md Contexte et règles du projet (à lire en premier)
├── CLAUDE.md              Règles pour les agents de code
├── CONTRIBUTING.md        Comment contribuer
└── CHANGELOG.md
```

Le dossier `backend/` (Worker Cloudflare) arrivera au jalon 2.

## Compiler le cœur

Prérequis : Rust stable. Le fichier `rust-toolchain.toml` installe le bon
canal avec `rustfmt` et `clippy` ; aucun autre outil n'est nécessaire pour le
cœur seul.

```sh
cargo build                          # cœur public
cargo build --features admin         # cœur avec le code d'administration
cargo test                           # tests du cœur
cargo test --features admin
cargo fmt --check
cargo clippy --all-targets -- -D warnings
```

Générer des liaisons à la main, à partir d'une bibliothèque compilée du cœur :

```sh
cargo build --release -p heywatchlist-core
scripts/generate-bindings.sh kotlin target/release/libheywatchlist_core.so /tmp/liaisons
```

(`swift` à la place de `kotlin` pour Swift ; `.dylib` à la place de `.so` sur
macOS.)

Pour les apps, on passe par les scripts de compilation, lancés par Gradle et
par la CI :

| Script | Rôle |
|---|---|
| `scripts/generate-bindings.sh` | Liaisons UniFFI (Kotlin ou Swift) depuis la bibliothèque compilée |
| `scripts/build-android-core.sh` | Cœur pour Android avec `cargo-ndk` (`arm64-v8a`, `x86_64`, API 31) et liaisons Kotlin |
| `scripts/build-ios-xcframework.sh` | Cœur pour iOS en XCFramework, liaisons Swift, version de l'app |

Détails par plateforme : `android/README.md` et `ios/README.md`.

## Comment la CI compile les apps

Le propriétaire n'a **pas de Mac** : l'app iPhone est compilée, testée puis
envoyée à TestFlight par GitHub Actions, sur un runner macOS. Le projet Xcode
est généré par XcodeGen à partir de `ios/project.yml`. Trois workflows, chacun
lancé seulement quand les fichiers qui le concernent changent :

| Workflow | Runner | Fait |
|---|---|---|
| CI cœur | Linux | Format, clippy et tests du cœur, avec et sans `admin` |
| CI Android | Linux | Tests de l'édition publique, APK admin de debug en artefact ; sur `main` ou un tag, et si les secrets de signature existent, APK admin signé et AAB public |
| CI iOS | macOS | XCFramework du cœur, XcodeGen, tests sur simulateur ; sur `main` ou un tag, et si les secrets existent, envoi sur TestFlight avec fastlane |

Les minutes macOS coûtent dix fois celles de Linux : le job iOS ne se lance
que si `core/`, `ios/`, `scripts/` ou les fichiers Cargo changent.

Le détail (déclencheurs, secrets à configurer, signature iOS) est dans
`docs/CI.md`, qui fait foi.

## Version

Une seule source : `[workspace.package] version` dans le `Cargo.toml` racine.
`versionName` (Android) et `MARKETING_VERSION` (iOS) la lisent. Toute pull
request qui change l'app monte cette version et ajoute son entrée en tête de
`CHANGELOG.md`.

## Règles du projet

- Toute la logique est dans `core/` ; les interfaces affichent.
- Le cœur est tolérant et ne plante jamais volontairement (`unwrap`, `expect`
  et `panic` interdits).
- Aucun identifiant, clé d'API ni URL de service en clair dans le code, les
  journaux ou les tests ; aucun secret dans le dépôt.
- Les tests n'appellent jamais les vrais services : réponses simulées.
- **Aucun tracker nommé ni préconfiguré**, nulle part.
- Textes de l'interface : français, dans les fichiers de ressources.

La liste complète est dans `CLAUDE.md` et `CONTRIBUTING.md`.

## Documentation

| Fichier | Contenu |
|---|---|
| `context_hey-torrent.md` | Contexte, rôles, architecture, règles, décisions (à lire en premier) |
| `docs/PLAN.md` | Plan par jalons |
| `docs/DECISIONS.md` | Décisions prises, avec leur date |
| `docs/DESIGN.md` | Identité visuelle (à lire avant toute interface) |
| `docs/CI.md` | Chaîne d'intégration continue |
| `docs/TODO.md` | Reste à faire |
| `CHANGELOG.md` | Historique des versions |

Dépôt privé, tous droits réservés.
