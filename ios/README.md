# App iPhone (SwiftUI)

Interface native de HeyWatchlist : SwiftUI, iOS 26 minimum (Liquid Glass),
iPhone seulement. Toute la logique vit dans le cœur Rust (`../core`) ; ici on
n'affiche que ce qu'il renvoie, et on traduit ses erreurs en français.

Il n'y a **pas de projet Xcode dans le dépôt** : le `.xcodeproj` est généré par
[XcodeGen](https://github.com/yonaskolb/XcodeGen) à partir de `project.yml`.
Tout est fait par la CI sur un runner macOS ; rien n'est à ouvrir à la main.

## Chaîne de compilation

Depuis la racine du dépôt, sur macOS (dans cet ordre) :

```sh
scripts/build-ios-xcframework.sh   # cœur -> XCFramework, liaisons Swift, version
cd ios && xcodegen generate        # project.yml -> HeyWatchlist.xcodeproj
```

Le script produit trois choses, toutes ignorées par git :

| Sortie | Rôle |
|---|---|
| `Frameworks/HeyWatchlistCore.xcframework` | bibliothèques statiques du cœur (appareil et simulateur) |
| `Generated/heywatchlist_core.swift` | liaisons UniFFI, compilées avec l'app |
| `Config/Version.xcconfig` | `MARKETING_VERSION` (lue dans `[workspace.package]` du `Cargo.toml` racine) et `CURRENT_PROJECT_VERSION` |

Le numéro de build (`CURRENT_PROJECT_VERSION`) vient de `BUILD_NUMBER` ou de
`GITHUB_RUN_NUMBER`, 1 par défaut. TestFlight exige qu'il augmente à chaque envoi.

`Config/Base.xcconfig` (versionné) inclut `Version.xcconfig` s'il existe. La
signature (`DEVELOPMENT_TEAM`, profils) est fournie par la CI, jamais écrite ici.

## Organisation

- `HeyWatchlist/Core/` : `CoreClient` (protocole), `LiveCoreClient` (appelle
  UniFFI), `CoreFailure` (type d'erreur vers texte), `CoreStatusModel` (état
  chargement / chargé / échec, observé par la vue).
- `HeyWatchlist/UI/` : `HomeView` (Liquid Glass sur un fond sombre) et `HeyTheme`
  (jetons de couleur communs avec Android).
- `HeyWatchlist/Resources/` : `Localizable.xcstrings` (français seulement, aucun
  texte en dur dans le code), `Assets.xcassets` (icône provisoire, couleurs),
  `PrivacyInfo.xcprivacy` (aucun suivi, aucune donnée collectée).
- `HeyWatchlistTests/` : tests du modèle avec des doublures de `CoreClient`,
  plus un test d'intégration (`LiveCoreClientTests`) qui appelle le vrai cœur.

## Tests

```sh
xcodebuild test -project HeyWatchlist.xcodeproj -scheme HeyWatchlist \
  -destination 'platform=iOS Simulator,name=iPhone 17'
```

Le nom du simulateur dépend de l'Xcode installé (`xcrun simctl list devices`).
