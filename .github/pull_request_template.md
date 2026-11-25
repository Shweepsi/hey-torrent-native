## Ce que change cette PR

<!-- Une ou deux phrases : le quoi et le pourquoi. -->

## Vérifications

- [ ] Si l'app change : `[workspace.package] version` montée dans le `Cargo.toml` racine et entrée ajoutée en tête de `CHANGELOG.md`.
- [ ] `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` et `cargo test` passent (aussi avec `--features admin` si le cœur change).
- [ ] La CI est verte (Android et iOS ne se compilent que là).
- [ ] Aucun secret, clé d'API, jeton ni vraie URL de serveur dans le dépôt ni dans les journaux.
- [ ] Aucun tracker nommé : ni dans le code, ni dans les tests, ni dans les textes, ni dans les messages de commit.
- [ ] Les textes affichés sont en français, dans les fichiers de ressources, jamais en dur.
- [ ] Le code d'administration reste dans `android/app/src/admin/` et derrière la fonctionnalité Cargo `admin`.

## Comment tester

<!-- Artefact de la CI, build TestFlight, commande à lancer… Dire ce qui n'a pas pu être vérifié. -->
