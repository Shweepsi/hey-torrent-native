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

- `HeyWatchlist/Core/` : la frontière avec le cœur Rust. Aucune règle métier ici.
  - Compte : `AccountClient` (protocole), `LiveAccountClient` (appelle UniFFI),
    `AccountFailure` (erreur du cœur vers texte), `AccountModel` (connexion,
    attente de validation, Réglages), `KeychainSessionStore`.
  - `CoreAccountSource` : l'**unique** `Account` du processus. Le compte et le
    catalogue le partagent, donc la même session et le même renouvellement du
    jeton (deux `Account` auraient chacun leur jeton).
  - Catalogue : `CatalogClient` (protocole et types de domaine : `Title`,
    `TitleSheet`, `SeasonEpisodes`…), `LiveCatalogClient` (acteur qui appelle
    `Catalog` d'UniFFI et traduit les types), `CatalogFailure` (erreur vers
    texte), `Loadable` (chargement, chargé, échec : un par rangée, fiche ou
    saison).
  - Modèles `@MainActor @Observable` : `DiscoverModel` (une rangée par liste,
    chargées et en échec indépendamment), `SearchModel` (délai de 350 ms,
    requête précédente annulée, réponses obsolètes ignorées, résultats du terme
    précédent gardés, estompés, jusqu'à la première page du nouveau),
    `DetailModel` (fiche et saison ouverte), `GenreModel` (grille d'un genre,
    films ou séries ; il passe l'identifiant du genre de film tel quel, la
    correspondance pour les séries est faite par le cœur, voir plus bas), et
    `TitlePager` (pages à défilement infini, partagé par la recherche et les
    genres).
- `HeyWatchlist/UI/` : toutes les vues et leurs briques.
  - Racine : `RootView` (accueil tant qu'on n'est pas connecté, sinon les
    onglets), `MainTabView` (onglets Découvrir, Watchlist, Réglages, et
    Rechercher en `Tab(role: .search)`), `WelcomeView` (accueil, connexion Apple
    ou Google), `WaitingView` (attente de validation du compte) et `SettingsView` (Réglages : prénom,
    déconnexion, suppression du compte, version).
  - Catalogue : `DiscoverView` (rangées, pastilles de genres), `HeroCarousel`
    (carrousel de tête), `TitleDetailView` (fiche) et `SeasonSheet` (épisodes
    d'une saison), `SearchView`, `GenreView`, `TitleGrid` (grille d'affiches à
    défilement infini, et son squelette).
  - Briques : `PosterView` (`RemoteImage`, `PosterView`, `PosterCard`,
    `PosterRow`), `Skeleton` (squelettes, bandeau hors ligne, carte d'échec),
    `TitleText` (textes tirés des types de domaine : intitulés des rangées,
    « Film · 2024 », durées d'épisode), `HeyTheme` (jetons de couleur communs
    avec Android, et le fond `HeyBackground`) et `Formatting` (nombres, durées
    et dates en français).
- `HeyWatchlist/Resources/` : `Localizable.xcstrings` (français seulement,
  aucun texte en dur dans le code, clés triées), `Assets.xcassets` (icône
  provisoire, couleurs), `PrivacyInfo.xcprivacy` (aucun suivi, aucune donnée
  collectée ; deux catégories d'API à raison requise, voir plus bas).
- `HeyWatchlistTests/` : Swift Testing. Doublures (`FakeAccountClient`,
  `FakeCatalogClient`), tests des modèles, de `CatalogFailure` et de la mise en
  forme, et des tests d'intégration (`LiveAccountClientTests`,
  `LiveCatalogClientTests`) qui appellent le vrai cœur lié à l'app, **sans
  réseau** : traduction des types, session absente, adresse absente.

### Types du cœur et types de l'app

Les liaisons UniFFI (`Generated/`) sont compilées dans le même module que
l'app. Un type de l'app ne peut donc pas porter le nom d'un type exporté par
le cœur (`TitleSummary`, `Genre`, `Video`, `Episode`, `Shelf`,
`MediaType`, `Catalog`…). Les types de domaine ont d'autres noms (`Title`,
`TitleGenre`, `EpisodeEntry`, `DiscoverShelf`, `TitleKind`…),
et seul `LiveCatalogClient.swift` connaît les deux. De même, le fournisseur
de connexion de l'app s'appelle `SignInProvider`.

### Catalogue et écrans

- **Images** : les adresses viennent du cœur, jamais construites ici.
  `AsyncImage` les affiche ; `HeyWatchlistApp` agrandit `URLCache.shared`
  (48 Mo en mémoire, 256 Mo sur disque) pour que les affiches déjà vues
  reviennent du disque.
- **Cache hors ligne** : le cœur ouvre un fichier SQLite dans le dossier Caches
  de l'app (`heywatchlist-catalog.sqlite`), à la première lecture. Un contenu
  lu dans ce cache faute de réseau porte `stale`, et l'interface affiche un
  bandeau discret.
- **Session perdue** : si le cœur ne parvient plus à renouveler le jeton,
  `LiveCatalogClient` prévient `AccountModel.sessionExpired()` et l'app revient
  à l'accueil.
- **Fiches** : `TitleRoute` porte le titre et l'identifiant de sa vignette ;
  `titleDestination(client:zoom:)` pose une seule fois la destination (zoom
  `navigationTransition(.zoom)`) sur la racine de chaque pile. Les titres
  similaires d'une fiche ouvrent leur propre fiche dans la même pile.
- **Données et défilement de Découvrir** : les modèles Découvrir et Recherche
  sont gardés par `MainTabView`, au-dessus des onglets ; leurs données
  survivent donc au changement d'onglet. La position de défilement, elle,
  repose sur `TabView`, qui garde normalement la vue de chaque onglet : à
  vérifier sur appareil.
- **Genres** : les pastilles de Découvrir viennent des genres de *films*, et
  l'identifiant reçu par `CatalogClient.titles(of:genre:page:)` est **toujours**
  celui d'un genre de film, pour les séries aussi. TMDB numérote à part les
  genres des séries (« Action » n'existe que côté films, « Action & Adventure »
  côté séries) : la correspondance est une règle métier, elle vit dans le cœur,
  qui traduit l'identifiant par une table fixe. Un genre sans équivalent chez les
  séries (« Horreur », par exemple) donne une page vide, sans appel réseau ; ici,
  `GenreView` l'écrit « Aucune série ne correspond à ce genre. » (`genre.noSeries`)
  pour le choix Séries, et « Aucun titre pour ce genre. » pour les films. Aucune
  lecture des genres de séries, aucune logique de correspondance dans l'app.
- **Annulation** : les liaisons UniFFI (0.32) ne regardent jamais l'annulation
  d'une tâche Swift ; un appel au cœur va à son terme. `LiveCatalogClient` ne
  lève donc jamais `CancellationError`, et c'est le numéro de génération de
  `TitlePager` qui écarte la réponse d'une requête remplacée.
- **Lisibilité** : aucun texte directement sur une affiche. Le nom et l'année
  sont sous l'affiche dans les rangées. Dans le carrousel, le texte est posé
  sur une bande à 88 % d'opacité du fond de l'app, précédée d'un fondu de 40 pt :
  la bande suit la taille du texte (Dynamic Type), et le contraste calculé
  (WCAG) sur un fond d'écran entièrement blanc reste de 14:1 pour le titre et de
  5,4:1 pour le sous-titre. Pas de rendu vérifié : à regarder sur une image
  claire au premier tour de CI. Pas de verre par élément des listes qui
  défilent (fonds unis), le verre ne sert qu'aux boutons et à l'onglet ;
  squelettes immobiles avec « Réduire les animations ».

### API à raison requise (`PrivacyInfo.xcprivacy`)

SQLite est compilé avec le cœur (`rusqlite`, fonctionnalité `bundled`) et
travaille dans le dossier Caches de l'app. Son code appelle deux catégories d'API
dont Apple exige une raison :

| Catégorie | Appels de SQLite | Raison déclarée |
|---|---|---|
| `NSPrivacyAccessedAPICategoryFileTimestamp` | `stat`, `fstat` (taille et dates de son fichier) | `C617.1` : fichiers du conteneur de l'app |
| `NSPrivacyAccessedAPICategoryDiskSpace` | `fstatfs`, `statfs` (reconnaître le système de fichiers pour choisir le verrouillage) | `E174.1` : la plus proche, **à confirmer par le propriétaire** |

Aucune raison de la catégorie « espace disque » ne correspond exactement : SQLite
n'en lit aucune valeur d'espace libre. `E174.1` (vérifier l'espace avant d'écrire
des fichiers) est la moins éloignée.

Compiler SQLite sans `SQLITE_ENABLE_LOCKING_STYLE` ne retire pas ces appels :
la fonction d'ouverture de fichier appelle `fstatfs` sur tout système Apple,
quelle que soit la valeur du drapeau (lu dans `sqlite3.c` de `libsqlite3-sys`
0.40.0). Ce que SQLite et la bibliothèque standard de Rust pourraient appeler
d'autre (horloge, par exemple) n'a pas été contrôlé : au premier envoi, lire
l'e-mail d'App Store Connect (ITMS-91053 signale toute API à raison non
déclarée) et compléter le manifeste.

## État

Le code du jalon 3 (catalogue, écrans Découvrir, fiche, recherche, genres) a
été relu mais **jamais compilé ni exécuté** : il n'y a ni Mac ni Swift dans
l'environnement de développement. Le premier tour de CI dira ce qu'il reste à
corriger (API d'iOS 26 en particulier : `Tab(role: .search)`,
`tabBarMinimizeBehavior`, `navigationTransition(.zoom)`).

## Tests

```sh
xcodebuild test -project HeyWatchlist.xcodeproj -scheme HeyWatchlist \
  -destination 'platform=iOS Simulator,name=<un iPhone de la liste>'
```

Le nom du simulateur dépend de l'Xcode installé (`xcrun simctl list devices`).
