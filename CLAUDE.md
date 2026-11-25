# CLAUDE.md — HeyWatchlist (hey-torrent-native)

Lire d'abord `context_hey-torrent.md` (rôles, architecture, règles ; en cas de
doute, ses §9 « Décisions » priment), `README.fr.md`, `CONTRIBUTING.md`,
`docs/DECISIONS.md`, `docs/PLAN.md` (le jalon en cours : rien d'autre),
`docs/TODO.md` (travail restant) et, avant toute interface, `docs/DESIGN.md`.

## Règles

- On ne code pas une option « ouverte » ou « par défaut, à confirmer »
  (`docs/DECISIONS.md`) sans que le propriétaire l'ait choisie.
- **Aucun code, image ou texte issu d'une app existante** (Overseerr, Plex,
  etc.). On s'inspire du fonctionnement, pas du code ni de l'apparence. L'ancien
  dépôt `hey-torrent` reste une référence pour les règles métier (scoring,
  catégories), jamais pour l'interface.
- Toute la logique va dans `core/` (Rust) ; les interfaces natives (SwiftUI,
  Compose) ne font qu'afficher. Aucune règle métier ni appel réseau en Swift ou
  Kotlin.
- Le cœur ne produit **aucun texte affiché** : il renvoie des types d'erreur
  que chaque interface traduit en français.
- Le cœur est **tolérant** : une entrée illisible d'une réponse est ignorée,
  jamais fatale pour toute une liste.
- **Pas de `unwrap`, `expect` ni `panic!`** dans le code de production, ni
  d'indexation qui peut déborder (clippy l'impose ; autorisés dans les tests).
  Erreurs typées avec `thiserror`, opérations longues en `async` sur `tokio`.
  Jamais `panic = "abort"` : UniFFI s'appuie sur le déroulement de la pile.
- Ne jamais journaliser identifiants, jetons, clés d'API, liens magnet ni URL
  contenant une clé, en clair.
- **Ne jamais appeler les vrais services** (TMDB, Plex, qBittorrent, sources
  Torznab ou Prowlarr, backend en production) depuis une session cloud, la CI
  ou un script automatique : les tests utilisent des réponses simulées
  (`wiremock`), y compris les échecs (délai dépassé, 401, JSON invalide,
  réponse partielle). Aucune vraie URL de serveur, clé ou jeton dans le dépôt.
- **Aucun tracker nommé, intégré ou préconfiguré**, nulle part : code, tests,
  jeux de données, textes, documentation, messages de commit.
- **Code d'administration** : uniquement sous `android/app/src/admin/` et
  derrière la fonctionnalité Cargo `admin` (désactivée par défaut). L'édition
  publique et le cœur public ne référencent aucun symbole admin. Pas d'édition
  admin sur iOS.
- Textes de l'interface : **français uniquement**, dans les fichiers de
  ressources (`strings.xml`, `Localizable.xcstrings`), jamais en dur.
- Style visuel : verre dépoli (`docs/DESIGN.md`). Android sans Material 3 (pas
  de dépendance `material3`) ; iOS avec le Liquid Glass du système, sans flou
  recréé à la main.
- Les liaisons UniFFI générées, les binaires, le projet Xcode et
  `google-services.json` ne sont **jamais versionnés**. Aucun secret dans le
  dépôt : ils passent par les secrets GitHub.
- **Version** : toute PR qui change l'app monte `[workspace.package] version`
  (`Cargo.toml` racine, source unique, relancer `cargo check` pour mettre à
  jour `Cargo.lock`) et ajoute son entrée en tête de `CHANGELOG.md`.
- Commits en **Conventional Commits** (`feat(core): …`, `fix(android): …`),
  une branche par sujet, fusion par pull request, jamais de push direct sur
  `main`.
- Code en anglais (identifiants), commentaires et documentation en français,
  concis. Pas de code mort. Scripts bash : `set -euo pipefail`, compatibles
  `shellcheck`.
- **Honnêteté** : ne jamais dire qu'on a compilé ou exécuté ce qu'on n'a pas
  compilé ou exécuté (Android et iOS ne se compilent que sur la CI ; pas de Mac
  ici). Ne pas décrire comme fait ce qui n'existe pas encore. Vérifier qu'une
  version de dépendance existe avant de l'écrire.

## Vérifications avant de livrer

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
cargo clippy --all-targets --features admin -- -D warnings
cargo test --features admin
```

App Android (voir `android/README.md`), depuis `android/` :

```sh
./gradlew testPublicDebugUnitTest assembleAdminDebug
```

iOS : compilé uniquement par la CI (`docs/CI.md`), pas de vérification locale.
