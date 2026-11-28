# HeyWatchlist pour Android

Projet Gradle (Kotlin DSL) : un module `:app`, Jetpack Compose sans Material 3,
cœur Rust relié par UniFFI. L'app affiche ce que renvoie le cœur : le compte
(connexion, réglages) et le catalogue (écran Découvrir, fiches, recherche).
Toute la logique est dans le cœur ; Kotlin traduit les erreurs en français et
affiche.

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
./gradlew testPublicDebugUnitTest testAdminDebugUnitTest assembleAdminDebug   # commande de la CI
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

`src/main/` ne référence aucun symbole admin. Réglages appelle
`EditionContent.SettingsExtras()`, objet défini dans `src/public/` (vide) et
dans `src/admin/`, où il affiche l'entrée de l'espace admin. L'espace admin
(`src/admin/java/app/heywatchlist/admin/`) est une activité à part,
`AdminActivity`, déclarée par le manifeste de `src/admin/` : Demandes (fiche,
recherche dans les sources, envoi à qBittorrent, refus), Suivi des
téléchargements, Serveurs (réglages chiffrés sur le serveur, copie chiffrée
par le Keystore sur l'appareil) et Accès (demandes d'accès à accepter ou
refuser, accès retirable, adresses acceptées d'avance). Les textes admin ne sont que
dans `src/admin/res/`.

## Version et signature

- `versionName` est lue dans `[workspace.package]` du `Cargo.toml` racine ;
  `versionCode` = `major * 10000 + minor * 100 + patch` (minor et patch < 100).
  Rien n'est dupliqué dans Gradle.
- La release est signée avec les variables d'environnement
  `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` et
  `ANDROID_KEY_PASSWORD` quand les quatre sont définies, sinon avec la clé de
  debug (poste de développement seulement).

## Notifications (Firebase)

Les push « Disponible sur Plex » passent par Firebase Cloud Messaging, sans le
plugin Google Services : `app/build.gradle.kts` lit `google-services.json`
(secret GitHub `GOOGLE_SERVICES_JSON` en base64, ou `app/google-services.json`
sur un poste, jamais versionné) et inscrit les valeurs de chaque édition dans
`BuildConfig.FIREBASE_*` ; `push/PushSetup.kt` initialise Firebase avec. Sans
ce fichier, l'app se compile et fonctionne, notifications coupées. Le texte
vient de `strings.xml` (clés `push_*`, envoyées par le serveur : ne pas les
renommer).

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

## Architecture

- `core/` : frontières avec le cœur. `AccountGateway` et `CatalogGateway` sont
  des interfaces Kotlin (types de domaine propres, indépendants d'UniFFI) ;
  `UniffiAccountGateway`, `UniffiCatalogGateway` et `CatalogMapping.kt` sont les
  seuls fichiers qui importent le code généré. Chaque échec est un enum
  (`AccountFailure`, `CatalogFailure`) qui porte son message français
  (`@StringRes`) ; un test vérifie qu'il n'y a pas deux fois le même message.
- `HeyWatchlistApp` crée `AppServices` : **un seul `Account` du cœur par
  processus**, partagé par la passerelle du compte et celle du catalogue (même
  session, même renouvellement du jeton). Le cache hors ligne du catalogue est
  un fichier de `noBackupFilesDir`.
- Session expirée en cours d'usage : `SessionAwareCatalogGateway` enveloppe le
  catalogue et, quand une lecture échoue en `CatalogFailure.SignedOut` (le cœur
  a déjà effacé la session), `AppServices.sessionExpired` émet ; l'`AccountViewModel`
  l'écoute et revient à l'écran d'accueil, avec « Votre session a expiré ».
  Les rangées de Découvrir ne restent donc pas sur un « Réessayer » sans issue.
- Aucun appel au cœur sur le fil principal : les passerelles s'exécutent sur
  `Dispatchers.IO`, et les ViewModels ne voient que les interfaces, ce qui
  permet de tester chaque écran sans bibliothèque native (passerelle simulée).
- `ui/` : un état scellé par écran (`ShelfState`, `SearchState`, `DetailUiState`,
  `GenreListState`…), un ViewModel, un écran `@Composable` qui ne fait
  qu'afficher cet état.

## Écrans

- `ui/discover/` : Découvrir (carrousel des tendances, huit rangées, genres,
  tirer pour rafraîchir, bandeau discret quand les données viennent du cache),
  recherche instantanée (attente de 350 ms après la dernière frappe, lecture
  précédente annulée, réponse obsolète ignorée, page suivante au défilement),
  grille d'un genre (films ou séries) et fiche d'un titre (bande-annonce, où
  regarder en France, distribution, saisons et épisodes, titres similaires).
  Chaque rangée charge et échoue séparément ; une lecture ratée ne remplace
  jamais ce qui est déjà affiché.
- Genres : la pastille porte un genre de film, et `GenreViewModel` le passe tel
  quel à `CatalogGateway.byGenre`, pour les films comme pour les séries. La
  correspondance avec les genres de séries (Action et Aventure, Fantastique et
  Science-Fiction, genres sans équivalent) est une règle du cœur : côté Kotlin,
  un genre sans équivalent en série n'est qu'une page vide, que l'onglet Séries
  écrit « Aucune série ne correspond à ce genre. ».
- Données venues du cache de l'appareil (`stale`) : le bandeau « Ces données
  viennent de l'appareil et peuvent ne pas être à jour. » s'affiche sur
  Découvrir, la fiche, la grille d'un genre et les épisodes d'une saison. La
  recherche n'est jamais mise en cache, donc n'en a pas.
- Les écrans ouverts depuis Découvrir (recherche, fiche, genre) forment une pile
  tenue par `DiscoverNavigator` : chaque étape porte ses ViewModels, libérés
  quand on la quitte. Ouvrir l'écran déjà au sommet (double toucher) ne fait
  rien. Le numéro d'une étape est un UUID : il ne se répète pas après la mort
  du processus, car l'état sauvegardé de l'écran (défilement, saisie) est rangé
  sous ce numéro, et `MainScreen` le supprime quand l'écran quitte la pile.
  `DiscoverViewModel` vit avec l'activité : les données et la position de
  défilement survivent au changement d'onglet et à la rotation.
- Recherche : tant qu'une nouvelle requête attend la fin de la frappe, les
  résultats précédents restent affichés mais leur page suivante n'est pas lue.
- Pas de bouton watchlist ni de pastille « Sur Plex » : ils viendront avec leur
  jalon.

## Interface

- `HeyTheme` : jetons de couleur sombres d'abord (`#4f9cf7`, `#0c0c0f`, verre
  `rgba(255,255,255,0.09)` avec filet de 1 px à 0,2), typographie, formes.
  Le thème suit le réglage clair/sombre du système ; la fiche d'un titre est
  toujours sombre (elle se pose sur un fond d'image) et, tant qu'elle est
  affichée, les icônes de la barre d'état sont claires (le réglage d'avant
  revient à la sortie).
- `GlassSurface` : flou par Haze. Sans `HazeState` fourni, ou quand Haze coupe
  le flou, la surface devient translucide et plus opaque, sans flou. Pas de flou
  par élément de liste : les listes utilisent des surfaces opaques.
- Aucun texte posé directement sur une affiche : un dégradé sombre (`Scrim`) ou
  une surface opaque passe dessous. Cibles tactiles de 48 dp au moins, libellés
  d'accessibilité sur les icônes et les images utiles. Le toucher est découpé
  à la forme du composant avant d'être posé (`clip` puis `clickable`), pour que
  le retour visuel suive la forme. Le geste de tirer pour rafraîchir n'est pas
  praticable avec TalkBack : l'action « Actualiser » est proposée sur le titre
  « Découvrir » de l'onglet.
- Les images (affiches, fonds, photos) sont chargées par Coil 3 (`HeyPoster`),
  avec le chargeur réseau OkHttp. Quand l'adresse d'une image change (l'aperçu
  de la liste, puis la même affiche en plus grand sur la fiche), la dernière
  image affichée reste dessous jusqu'à la fin du fondu de la nouvelle. La version est figée à 3.3.0 : les suivantes
  sont compilées avec un Kotlin plus récent que le nôtre (voir
  `gradle/libs.versions.toml`).
- Les animations (squelettes, rotation) se figent quand la personne les a
  supprimées dans les réglages d'accessibilité d'Android.
- Les icônes sont dessinées dans `HeyIcons` (vecteurs), sans dépendance
  d'icônes.
- Les textes sont dans `res/values/strings.xml` (français uniquement, pluriels
  compris).

## Tests

`./gradlew testPublicDebugUnitTest` lance les tests JVM de `app/src/test/` :
ViewModels (compte, Découvrir, recherche, fiche, genre), pile de navigation
(doubles ouvertures, numéros d'étape uniques), formats, traduction des types
UniFFI, unicité des messages d'échec, signalement de la session expirée
(`SessionAwareCatalogGateway`). Les ViewModels sont testés avec une passerelle
simulée (`FakeCatalogGateway`) et un temps virtuel (`MainDispatcherRule`) :
aucun test n'appelle un vrai service. Les écrans Compose et la liaison native
n'ont pas de test automatisé : ni le bandeau, ni l'attribution, ni les icônes
de la barre d'état, ni le repli d'image de `HeyPoster`, ni l'action
« Actualiser » ne sont couverts.

`./gradlew testAdminDebugUnitTest` lance en plus les tests de
`app/src/testAdmin/` : ViewModels de l'espace admin (recherche, envoi, statut
non enregistré puis nouvel essai, refus, listes, réglages des serveurs), avec
une passerelle simulée (`FakeAdminGateway`).

### Captures d'écran

Les écrans sont rendus en PNG sur la JVM par Robolectric (graphismes natifs,
rendu matériel pour les flous) et Roborazzi, avec des données inventées
(`screenshots/SampleData.kt`) et des images factices dessinées à la volée :
aucune affiche réelle dans le dépôt, aucun appel réseau. Elles ne tournent
qu'avec `-Pscreenshots` (et alors seules) :

```sh
./gradlew testPublicDebugUnitTest testAdminDebugUnitTest -Pscreenshots
```

Les PNG vont dans `app/build/captures/`. Sur la CI, le job « Captures
d'écran » les publie dans l'artefact `captures-android` à chaque pull request
Android : de quoi juger une interface sans installer l'app. Ce ne sont pas des
tests de non-régression (rien n'est comparé) ; les animations infinies y sont
figées.

Pour un avant/après, lancer « CI Android » à la main (Actions, Run workflow)
avec `captures_ref` = le SHA d'un commit plus ancien qui a déjà ces tests :
le job rend alors ce commit. Lancé à la main, il écrit aussi chaque capture
dans son journal (JPEG réduit en base64, entre `=== capture <nom> ===` et
`=== fin ===`).

## Règles R8

`app/proguard-rules.pro` garde JNA et `uniffi.**` : le code généré charge la
bibliothèque native par réflexion.

Aucune règle propre à Coil n'est ajoutée. Son chargeur réseau OkHttp s'enregistre
par le mécanisme de services Java (`META-INF/services`) : à vérifier sur une
release si les images ne s'affichent plus.
