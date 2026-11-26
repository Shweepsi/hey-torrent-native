# Contexte du projet : HeyWatchlist (ex-HeyTorrent), natif iOS & Android

> Ce fichier vit à la racine du dépôt `hey-torrent-native`. Il guide les
> développeurs et les agents de code (Claude Code, etc.), comme un `CLAUDE.md`.
> **À lire en entier avant toute modification.** En cas de doute, la
> section « Décisions » (§9) prime : on ne code pas une option qui n'a pas été
> tranchée.

---

## 0. Statut (2026-11-25)

- **Nom public : HeyWatchlist.** Dépôt : `hey-torrent-native` (privé).
- **Préparation terminée côté propriétaire** : dépôt GitHub, compte Apple
  Developer, compte Cloudflare payant, clé TMDB, Firebase (FCM) ajouté,
  Plex et qBittorrent joignables en HTTPS.
- **Pas de Mac** : l'app iPhone est compilée et envoyée à TestFlight par
  GitHub Actions.
- **En cours** : jalon 1 (fondations et chaîne de compilation), voir
  `docs/PLAN.md`.
- **Décidé** : §1 à §9 ci-dessous, y compris le style verre dépoli (§7.1),
  les trois onglets, les deux apps (§1.3) et la voie honnête vers les stores
  (§9.4). **Retenu par défaut, à confirmer** : §9.6.

---

## 1. Ce qu'est le projet

**L'esprit de HeyTorrent : les proches demandent, l'administrateur exauce.**

HeyWatchlist (nom de travail historique : HeyTorrent) relie le propriétaire
d'un serveur Plex aux personnes avec qui il le partage.

- **Les utilisateurs** (famille, amis invités) parcourent les films et séries
  via **TMDB** et les ajoutent à leur **watchlist**. Chaque ajout est une
  **demande** adressée à l'administrateur. Ils suivent l'état de leurs
  demandes et reçoivent une notification quand le média est **disponible sur
  Plex**.
- **L'administrateur** (le propriétaire du serveur, Loïc) voit toutes les
  demandes, cherche la meilleure release francophone via **ses** sources
  Torznab (son **Prowlarr**), l'envoie à **son qBittorrent** avec la bonne
  catégorie, et Plex fait le reste.

**En une phrase : un Overseerr natif, centré sur TMDB.**

- Comme Overseerr : des utilisateurs invités font des demandes, un
  administrateur les traite, tout le monde est prévenu quand c'est sur Plex.
- Différences voulues :
  - **Apps natives** iPhone et Android (pas une page web), rapides et
    économes.
  - **Découverte TMDB riche** au cœur de l'expérience : tendances, nouveautés,
    recommandations « parce que vous avez aimé… », fiches complètes
    (casting, bandes-annonces, saisons, plateformes), tout en français.
  - **Pas de Sonarr ni Radarr** : le cœur fait lui-même la recherche
    Torznab, le scoring des releases francophones et l'envoi à qBittorrent.
- On s'inspire du **fonctionnement** d'Overseerr, pas de son code ni de son
  apparence.

### 1.1 Rôles

| | Utilisateur | Administrateur |
|---|---|---|
| Parcourir TMDB, fiches, bandes-annonces | Oui | Oui |
| Watchlist = demandes | Oui (les siennes) | Oui (les siennes + celles de tous) |
| Voir l'état d'une demande | Oui | Oui |
| Recherche Torznab, scoring, choix d'une release | **Non** | Oui |
| Pilotage de qBittorrent | **Non** | Oui |
| Réglages des serveurs (Prowlarr, qBittorrent, Plex) | **Non** | Oui |
| Inviter, retirer un utilisateur | Non | Oui |
| Notification « Disponible sur Plex » | Oui | Oui |

Un utilisateur ne voit **jamais** les sources, les torrents, qBittorrent ni
aucun identifiant de serveur. Le rôle est porté par le backend (D1). Les écrans
d'administration n'existent que dans l'app admin Android (§1.3), et le Worker
refuse toute action d'administration à un autre compte.

**États d'une demande :** `demandée` → `en cours` (envoyée à qBittorrent) →
`disponible` (vue sur Plex). Aussi : `refusée` (avec un motif court
optionnel) et `déjà disponible` (le média était déjà sur Plex au moment de la
demande : pas de demande créée, l'app le dit tout de suite).

### 1.2 Priorités : découverte, puis watchlist, puis espace admin

L'ordre compte, pour l'importance dans l'app **et** pour l'ordre de
construction.

1. **Découvrir (cœur de l'app, écran d'accueil)** : tout est centré sur la
   découverte TMDB. Tendances du jour et de la semaine, nouveautés au cinéma
   et en streaming, populaires, prochaines sorties, recommandations
   personnalisées à partir de la watchlist, parcours par genre, recherche
   instantanée, fiches riches (affiche et fond en grand, résumé, casting,
   bandes-annonces, saisons et épisodes, plateformes de diffusion en France,
   films similaires). Badge « Sur Plex » sur les médias déjà disponibles.
   C'est l'écran le plus soigné, celui qui donne envie.
2. **Watchlist** : ce que je veux voir, avec l'état de chaque demande
   (demandée, en cours, disponible, refusée). Ajout en un geste depuis
   n'importe quelle affiche ou fiche.
3. **Espace admin** : file des demandes, recherche Torznab et choix de la
   release, suivi qBittorrent, réglages des serveurs, invitations. Il vit
   **uniquement dans l'app admin Android** (voir §1.3), jamais dans les apps
   publiques.

Navigation : une barre d'onglets en verre dépoli, **Découvrir · Watchlist ·
Réglages**, identique pour tout le monde (« Réglages », le mot français
d'iOS, puisque l'app est en français).

- **Réglages** : compte, notifications, « À propos » (attribution TMDB),
  conditions et confidentialité, déconnexion, suppression du compte.
- Navigation selon les codes d'**iOS 26** : barre d'onglets flottante en
  Liquid Glass qui se rétracte au défilement, **recherche dans une bulle
  séparée** à droite des onglets, fiches en plein écran avec l'affiche qui
  s'agrandit depuis la liste.

### 1.3 Deux apps : publique et admin (décidé)

- **HeyWatchlist** (App Store et Play Store) : la partie utilisateurs
  seulement, Découvrir · Watchlist · Réglages. **Aucun code
  d'administration** n'y est compilé : ni recherche Torznab, ni scoring, ni
  qBittorrent, ni réglages des serveurs.
- **HeyWatchlist Admin** (Android uniquement, **APK installé en privé** par
  l'administrateur, jamais publié sur un store) : toute l'app publique **plus**
  l'espace admin.
- Mise en œuvre : un seul projet Android avec deux variantes Gradle
  (`productFlavors` : `public` et `admin`, identifiants d'app différents).
- **Identifiants d'app (décidés le 2026-11-25, définitifs après publication)** :
  app publique Android et iOS `io.github.shweepsi.heywatchlist` ; app admin
  Android `io.github.shweepsi.heywatchlist.admin` (`applicationIdSuffix
  ".admin"`). Projet Firebase `heywatchlist-60448`, où les deux apps Android
  sont déclarées ; `google-services.json` n'est jamais commité
  (`android/app/google-services.json` dans `.gitignore`), la CI le recrée
  depuis le secret GitHub `GOOGLE_SERVICES_JSON` (base64).
  Côté cœur Rust, le code d'administration est derrière une fonctionnalité
  Cargo `admin`, activée seulement pour la variante admin : l'app publique ne
  contient même pas ce code.
- Le Worker refuse toute action d'administration à un compte qui n'est pas
  administrateur, quelle que soit l'app.
- Rien n'est caché aux équipes de validation : l'app publique ne fait que ce
  qu'elle montre.

Cette version est une **réécriture complète** en applications mobiles natives,
dans un **nouveau dépôt** (`hey-torrent-native`). L'ancien dépôt `hey-torrent`
reste une référence fonctionnelle : on peut s'en inspirer pour les règles métier
(scoring, catégories), mais on n'y copie pas de code d'interface.

Objectifs : réactivité instantanée, consommation de batterie minimale,
stabilité.

---

## 2. Architecture : « Cerveau partagé en Rust, habillage natif »

1. **Le cœur (Rust, `/core`)** : toute la logique. Réseau, calculs, base
   locale, appels aux API.
2. **Le pont (Mozilla UniFFI)** : génère automatiquement les liaisons Swift et
   Kotlin depuis le Rust. Aucun code C ou JNI écrit à la main.
3. **Les interfaces 100 % natives** :
   - iPhone : **SwiftUI** (Human Interface Guidelines, animations fluides,
     retour haptique).
   - Android : **Jetpack Compose**, avec un style visuel propre à HeyWatchlist
     (pas l'apparence Material 3 par défaut, voir §7.1), tout en respectant
     les conventions Android : navigation par gestes, bouton retour, barres
     système.

---

## 3. Règle d'or : qui fait quoi ?

### Le cœur Rust (et seulement lui)

- Authentification auprès du backend Cloudflare (Worker) et rafraîchissement
  des jetons.
- Appels à l'API TMDB.
- *(Administrateur)* Recherche sur les sources Torznab de l'administrateur
  **en parallèle**, avec un délai maximal de **6 secondes par source** (et par
  indexeur quand Prowlarr renvoie le détail). Une source lente ou en panne
  est ignorée ; les résultats des autres sont renvoyés.
- *(Administrateur)* Tri et notation des résultats : résolution (2160p/4K, 1080p, 720p), codec
  (x264, x265/HEVC, AV1), source (WEB-DL, BluRay…), langue (priorité VFF / VF /
  MULTI / TRUEFRENCH), nombre de seeders.
- *(Administrateur)* Pilotage de qBittorrent : ajouter, mettre en pause, reprendre, supprimer,
  **catégoriser** (films / séries).
- Vérification de disponibilité sur Plex (via le Worker pour les
  utilisateurs, qui n'ont pas accès au serveur).
- Watchlist et demandes : création, états, synchronisation avec le Worker.
- Base locale **SQLite** (cache et consultation hors ligne).
- Enregistrement de l'appareil pour les notifications push (voir §9.5).
- *(Administrateur)* Envoi et récupération des identifiants des serveurs,
  chiffrés, auprès du backend (voir §9.3).

### Les interfaces Swift et Kotlin (et seulement elles)

- Afficher les écrans : listes, affiches, boutons, tiroirs, fenêtres modales.
- Réagir aux interactions tactiles.
- Demander les permissions (notifications).
- Garder les secrets sur le téléphone dans le coffre-fort du système :
  **Keychain** sur iOS, **Keystore** (via DataStore chiffré) sur Android. Cela
  couvre les jetons de connexion et la copie locale des identifiants des
  services. Le cœur ne persiste jamais un secret en clair lui-même :
  l'interface le lui fournit au démarrage.
- Recevoir les notifications push, jouer les vibrations haptiques.
- **Traduire les erreurs** du cœur en texte français affiché.

> Aucun calcul de tri, aucun appel direct à une source d'indexation, à qBittorrent, à Plex
> ou à TMDB, aucune règle métier dans Swift ou Kotlin. Tout passe par le cœur.

---

## 4. Organisation du dépôt (monorepo)

```
hey-torrent-native/
├── Cargo.toml      Espace de travail Rust ; version de l'app (source unique)
├── core/           Cœur Rust, tests, UniFFI en macros (pas de fichier UDL)
├── tools/
│   └── uniffi-bindgen/   Génère les liaisons Kotlin et Swift
├── android/        Projet Gradle (Compose), variantes public et admin
├── ios/            Projet décrit par project.yml (XcodeGen), fastlane
├── backend/        Worker Cloudflare (API, D1, push), TypeScript
├── scripts/        Compilation du cœur (Android, XCFramework), liaisons
├── docs/           PLAN.md, DECISIONS.md, DESIGN.md, CI.md, TODO.md
├── .github/        CI GitHub Actions, dependabot
├── CLAUDE.md       Règles pour les agents de code
├── CHANGELOG.md
└── context_hey-torrent.md   (ce fichier)
```

Les fichiers générés par UniFFI, les binaires compilés et le projet Xcode
(`.xcodeproj`, généré par XcodeGen) ne sont **pas** versionnés : ils sont
produits par `scripts/` et par la CI.

---

## 5. Plateformes visées

Appareils récents uniquement. Valeurs par défaut (modifiables) :

| Plateforme | Minimum | Pourquoi |
|---|---|---|
| iOS | **iOS 26** | Liquid Glass natif partout, sans repli ; codes visuels d'iOS 26 (prêt pour 2027) |
| Android | **Android 12 (API 31)**, cible la dernière API | Flou natif (`RenderEffect`) pour le verre dépoli, gestes, thème sombre |

Architectures compilées : `aarch64-apple-ios`, `aarch64-apple-ios-sim`,
`aarch64-linux-android`, `x86_64-linux-android` (émulateur).

Langue de l'interface : **français uniquement**. Pas de mécanisme multilingue
à prévoir, mais les textes restent regroupés dans les fichiers de ressources
de chaque plateforme (`Localizable.xcstrings`, `strings.xml`), jamais en dur
dans le code.

Hors périmètre : widgets, Live Activities, Dynamic Island, montre, tablette
optimisée, Android TV.

---

## 6. Services externes

| Service | Accès | Notes |
|---|---|---|
| TMDB | API v3/v4 officielle, via le Worker avec **la clé de l'app** | Langue des réponses : `fr-FR`. Mention obligatoire du logo et de la phrase d'attribution TMDB dans l'écran « À propos » |
| Sources d'indexation | **API Torznab** (Prowlarr, Jackett ou tout service compatible), **ajoutées par l'administrateur** (URL + clé d'API) | **Aucun scraping HTML. Aucun tracker nommé ou préconfiguré dans l'app** (voir §9.4). Un client Torznab générique derrière un trait commun |
| qBittorrent | Web API en **HTTPS** depuis l'extérieur | Compatible v4.x **et** v5.x ; catégorie obligatoire |
| Plex | API en **HTTPS** ; serveur dans un conteneur Docker sur **Unraid** | Recherche par identifiant TMDB/IMDb quand c'est possible ; source des push « Disponible sur Plex » |
| Cloudflare (Workers + D1) | API HTTPS du backend personnel | Connexion, synchro, identifiants chiffrés et push : voir §6.1 et §9 |

### 6.1 Backend : Cloudflare

Décidé par le propriétaire : le backend est sur **Cloudflare**, pas sur
Supabase.

- **Workers** (TypeScript ou Rust via `workers-rs`) : la petite API du
  backend : connexion, synchro, enregistrement des appareils, envoi des push.
- **D1** (base SQLite gérée) : comptes, watchlist synchronisée, jetons
  d'appareil pour les push. Même moteur que la base locale du téléphone, ce
  qui simplifie la synchro.
- **Secrets Workers** (`wrangler secret`) : clés APNs et FCM, clé de
  signature des jetons. Jamais dans le code ni dans `wrangler.toml`.
- **Queues** ou **Cron Triggers** si un traitement en tâche de fond devient
  nécessaire.
- Le code du backend vit dans un dossier `/backend` du monorepo, déployé
  avec `wrangler`. Le cœur Rust ne parle qu'à cette API, jamais directement
  à D1.
- Le Worker du backend se déploie sous le nom **`heywatchlist-api`**
  (`name = "heywatchlist-api"` dans `wrangler.toml`) : un Worker vide de ce
  nom existe déjà avec le secret `FCM_SERVICE_ACCOUNT` (compte de service FCM,
  rôle FCM uniquement). Un autre nom perdrait ce secret.
- **Cloudflare Tunnel** est une option possible (non obligatoire) pour
  exposer qBittorrent et Plex en HTTPS sans ouvrir de port chez soi.

Règles réseau :

- HTTPS obligatoire partout ; aucun contournement de la vérification TLS.
- Délai d'attente explicite sur chaque requête.
- Respecter les limites de débit des sources d'indexation et de TMDB (pas de rafales,
  pas de relance en boucle).
- Les tests n'appellent **jamais** les vrais services : ils utilisent des
  réponses simulées (`wiremock`) et des réponses réelles enregistrées puis
  anonymisées.

---

## 7. Expérience utilisateur

- **Fluidité absolue** : l'écran ne gèle jamais. Dès qu'une recherche part,
  l'interface affiche des squelettes de chargement.
- **Résultats progressifs** : les résultats des sources s'affichent dès
  qu'ils arrivent, sans attendre le plus lent.
- **Tolérance aux pannes** : une source en panne est ignorée au bout de
  6 secondes, avec une indication discrète (« Une source n'a pas répondu »,
  avec le nom que l'utilisateur lui a donné).
- **Hors ligne** : la watchlist et les fiches déjà vues restent consultables.
- **Mode sombre natif** : suit automatiquement le thème du téléphone.
- **Sécurité** : aucune clé d'API ni identifiant en clair dans un fichier,
  un journal ou un rapport de plantage.

### 7.1 Style visuel : verre dépoli moderne

Décidé par le propriétaire : un **thème HeyWatchlist sur mesure**, moderne, à
base de **verre dépoli** (glassmorphism), et **pas** l'apparence Material 3 par
défaut.

- **Principe** : surfaces translucides et floutées (barres de navigation,
  barre d'onglets, tiroirs, fenêtres modales, cartes au-dessus des affiches)
  posées sur des fonds riches : affiches et fonds TMDB floutés, dégradés
  doux. Coins très arrondis, fines bordures claires, ombres légères,
  typographie nette et grande pour les titres.
- **iPhone** : utiliser les matériaux natifs d'Apple. **Liquid Glass**
  (`.glassEffect`, `GlassEffectContainer`, onglets `Tab` avec rôle
  `.search`, `tabBarMinimizeBehavior`), disponible partout puisque le
  minimum est iOS 26. Ne pas recréer le flou à la main.
- **Android** : thème Compose maison (`HeyTheme` : palette claire et sombre,
  typographie, formes, une dizaine de composants) construit sur Compose
  Foundation, sans composants Material visibles. Flou par
  `Modifier.blur` / `RenderEffect` ou la bibliothèque **Haze** (minimum
  Android 12, donc flou disponible partout). Les
  comportements restent ceux d'Android (retour, gestes, barres système).
- **Lisibilité d'abord** : le texte sur verre garde un contraste suffisant
  (WCAG AA), en clair comme en sombre. Respecter les réglages système
  « Réduire la transparence » (iOS) et « Supprimer les animations » : le
  verre devient alors une surface opaque.
- **Batterie et fluidité** : le flou coûte cher. On le réserve aux surfaces
  fixes (barres, modales), jamais à chaque élément d'une longue liste qui
  défile. Viser 60 i/s minimum sur un appareil moyen.
- **Animations** : transitions courtes et naturelles (ressorts), affiche qui
  s'agrandit vers la fiche détail, squelettes de chargement animés.
- Le détail (couleurs exactes, composants, maquettes) vivra dans
  `docs/DESIGN.md`, à lire avant toute interface.

---

## 8. Consignes pour les agents et développeurs

### 8.1 Rust (cœur)

- **Jamais de plantage volontaire** : pas de `unwrap()`, `expect()`, `panic!`,
  ni d'indexation qui peut déborder dans le code de production. Clippy
  l'impose :
  ```toml
  [lints.clippy]
  unwrap_used = "deny"
  expect_used = "deny"
  panic = "deny"
  ```
  (autorisés uniquement dans les tests).
- **Erreurs explicites** : chaque fonction faillible renvoie `Result<T, E>`.
  Erreurs typées avec **`thiserror`**, exposées à UniFFI comme énumérations.
  Le cœur ne produit **aucun texte affiché** : il renvoie un type d'erreur
  (`SourceTimeout`, `QbitAuthFailed`, `PlexUnreachable`…), que chaque
  interface traduit.
- **Tolérance** : une entrée illisible dans une réponse (un torrent mal formé,
  un film sans affiche) est ignorée, jamais fatale pour toute la liste.
- **Asynchrone** : toutes les opérations longues (réseau, SQLite) sont `async`,
  sur un runtime **`tokio`** unique géré par le cœur. UniFFI expose des
  fonctions `async` qui deviennent `async/await` en Swift et des fonctions
  `suspend` en Kotlin.
- **Bibliothèques de référence** (à confirmer au démarrage) : `reqwest` avec
  `rustls` (pas d'OpenSSL), `serde`, `rusqlite` ou `sqlx` (SQLite), `tracing`
  pour les journaux, `wiremock` pour les tests.
- **Journaux** : jamais d'identifiant, de jeton, de clé d'API, de lien magnet
  ni d'URL contenant une clé d'API ou une passkey en clair. Masquer avant
  d'écrire.
- **qBittorrent** : détecter la version à la connexion et gérer les écarts
  entre v4 et v5 (noms de routes, champs). Tout ajout de torrent porte une
  catégorie (`films` ou `series`, configurables).

### 8.2 Swift et Kotlin (interfaces)

- iOS : respecter les **Human Interface Guidelines** (Apple).
- Android : respecter les conventions de comportement Android (retour,
  gestes, accessibilité, tailles de cible tactile de 48 dp). L'apparence suit
  le style HeyWatchlist décrit en §7.1, pas le look Material 3 par défaut.
- Aucun appel réseau direct. Aucune règle métier.
- Tout appel au cœur se fait hors du fil principal (`Task` / coroutines).
- Kotlin : `ktlint`. Swift : `swift-format`.

### 8.3 Tests (obligatoires)

- Toute fonctionnalité du cœur arrive avec ses tests.
- Le scoring des torrents est couvert par des tests sur des noms de releases
  réels (jeu de cas dans `core/tests/fixtures/`).
- Chaque client (TMDB, Torznab, qBittorrent v4 **et** v5, Plex) est testé
  contre des réponses simulées, y compris les cas d'échec : délai dépassé,
  401, JSON invalide, réponse partielle.
- Les interfaces ont au minimum des tests unitaires de leurs modèles d'écran
  (ViewModel).

### 8.4 Vérifications avant de livrer

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
cargo clippy --all-targets --features admin -- -D warnings
cargo test --features admin
```

Android, depuis `android/` : `./gradlew testPublicDebugUnitTest assembleAdminDebug`
(deux variantes : les tâches portent le nom de la variante, pas
`testDebugUnitTest`).
iOS : **pas de Mac en local**. Tout se compile sur GitHub Actions (voir
§8.5) ; on teste sur un vrai iPhone via TestFlight.

### 8.5 Git et CI

- **Conventional Commits** : `feat:`, `fix:`, `refactor:`, `test:`, `docs:`,
  `chore:`, `ci:`, avec une portée si utile (`feat(core): …`,
  `fix(android): …`).
- Une branche par sujet, fusion par pull request. Pas de push direct sur `main`.
- **GitHub Actions** sur chaque PR :
  - cœur : fmt, clippy, tests (Linux) ;
  - Android : compilation du cœur pour Android + build Gradle + tests ;
  - iOS : sur un runner **macOS** (`macos-latest`, dernier Xcode) :
    compilation du cœur en XCFramework, build et tests sur simulateur ;
  - sur `main` (ou un tag) : signature et envoi sur **TestFlight** avec
    **fastlane** (certificats via `fastlane match` ou secrets GitHub, clé
    d'API App Store Connect en secret GitHub) ;
  - Android : APK signé de la variante **admin** publié en artefact de la CI
    (ou en release GitHub privée), et AAB de la variante **public** pour le
    Play Store.
- Les minutes macOS de GitHub Actions coûtent plus cher que Linux : ne lancer
  le job iOS que si `core/`, `ios/`, `scripts/` ou les fichiers Cargo
  changent (même règle qu'en §9.6).
- **Versions** : version sémantique dans `[workspace.package]` du `Cargo.toml`
  racine, reprise par les deux apps. Toute PR qui change l'app monte la
  version et ajoute son entrée en tête de `CHANGELOG.md`.
- Aucun secret dans le dépôt. Les secrets de CI passent par les secrets
  GitHub.

---

## 9. Décisions

Décisions prises le 2026-11-25 par le propriétaire, sauf mention « ouverte ».
Un agent ne code pas une option ouverte sans que le propriétaire l'ait
choisie ; il peut préparer le code pour que le choix reste facile.

### 9.1 Connexion au compte : Apple et Google (décidé)

- « Se connecter avec Apple » sur iPhone, « Se connecter avec Google » sur
  Android (les deux proposés sur chaque plateforme si possible).
- Le téléphone obtient le jeton d'identité d'Apple ou de Google et le passe au
  cœur, qui l'envoie au Worker.
- Le Worker vérifie la signature de ce jeton (clés publiques d'Apple et de
  Google), puis émet son propre **jeton d'accès court** (JWT, environ
  15 minutes) et un **jeton de rafraîchissement** (stocké haché dans D1,
  révocable).
- Le cœur rafraîchit le jeton d'accès automatiquement. Les jetons sont gardés
  dans le Keychain ou le Keystore.
- Pas de mot de passe HeyWatchlist, pas de Cloudflare Access.
- **Sur invitation** : l'administrateur crée un lien ou un code d'invitation
  dans l'app ; une personne qui se connecte sans invitation valide n'a accès
  à rien. Le premier compte, créé au déploiement, est l'administrateur.
- Sur iPhone, « Se connecter avec Apple » est **obligatoire** dès qu'une autre
  connexion (Google) est proposée (Apple 4.8).
- **Suppression du compte depuis l'app** (Apple 5.1.1(v), Google Play) :
  efface le compte, la watchlist, les identifiants chiffrés et les jetons
  d'appareil dans D1, et révoque la connexion Apple.

### 9.2 Watchlist et demandes, synchronisées (décidé)

- **D1 est la source de vérité** des watchlists et des demandes ; SQLite sur
  le téléphone sert de cache hors ligne.
- Ajouter un média à sa watchlist **crée une demande** (une seule par média :
  si plusieurs personnes veulent le même film, elles sont toutes rattachées à
  la même demande et toutes notifiées).
- Retirer un média de sa watchlist ne supprime la demande que si personne
  d'autre ne l'attend et qu'elle n'est pas encore `en cours`.
- Pour les séries : demande de la série entière ou de saisons au choix
  (décidé par le propriétaire le 2026-11-26).
- Règle de conflit : **la dernière modification gagne, champ par champ**,
  avec un horodatage `updated_at` posé par le Worker ; suppression logique
  (`deleted_at`).
- Les modifications hors ligne sont mises en file d'attente dans SQLite et
  envoyées au retour du réseau.
- Le Worker filtre chaque requête par compte et par rôle : un utilisateur ne
  voit que ses propres demandes, l'administrateur voit tout.

### 9.3 Identifiants des serveurs : administrateur seulement, chiffrés sur Cloudflare (décidé)

Concerne : URL et clés d'API des sources Torznab (Prowlarr…), identifiants
qBittorrent, jeton Plex. La clé TMDB n'en fait pas partie : c'est la clé de
l'app, gardée en secret Workers, et le Worker relaie les appels TMDB (cache
possible), pour que l'utilisateur n'ait rien à saisir.

- Saisis **une seule fois par l'administrateur** dans l'écran « Réglages
  des serveurs », avec un
  bouton « Tester la connexion » par service.
- Envoyés au Worker et stockés **chiffrés** dans D1 (AES-256-GCM, clé de
  chiffrement gardée en secret Workers, jamais dans D1 ni dans le code).
- Récupérés à la connexion sur un nouvel appareil, puis gardés en copie locale
  dans le Keychain ou le Keystore pour fonctionner hors ligne.
- Le Worker ne journalise jamais ces valeurs et ne les renvoie qu'au compte
  administrateur. Les utilisateurs n'en reçoivent jamais aucune.
- Le cœur continue de recevoir ses secrets de l'interface ; il ne sait pas
  d'où ils viennent.
- La politique de confidentialité dit clairement que le backend peut
  déchiffrer ces identifiants pour les renvoyer à leur propriétaire.

### 9.4 Distribution : la voie honnête vers les stores (décidé)

Objectif : **publier sur l'App Store et le Play Store** sans rien cacher aux
équipes de validation. Pour les utilisateurs, l'app est une **watchlist avec
demandes** pour le serveur Plex d'un proche (comme Overseerr ou Ombi). L'espace
admin n'est jamais publié : il vit dans l'APK Android privé (§1.3).

**Règles produit (obligatoires) :**

- **Aucun tracker nommé, intégré ou préconfiguré** dans le code, les textes,
  la fiche store, les captures ou la démo. L'administrateur ajoute ses propres
  sources Torznab (URL + clé d'API).
- L'app publique ne fournit, n'héberge et ne recommande **aucun contenu**
  à télécharger. Elle affiche les données TMDB, gère la watchlist et les
  demandes, et indique ce qui est disponible sur le Plex de l'administrateur.
- **Aucune fonction cachée** ni comportement différent pendant la revue
  (Apple 2.3.1). Ce que voit la revue est exactement l'app livrée. L'app
  publique ne contient pas l'espace admin (§1.3) ; la revue reçoit un compte
  de démo utilisateur.
- Fiche store, description et captures décrivent fidèlement l'app :
  « découvrez des films et séries, demandez-les au serveur Plex de vos
  proches, soyez prévenu quand ils sont disponibles ».
- Écran de démarrage qui explique que l'app se connecte aux serveurs
  personnels de l'administrateur, et rappel dans les conditions
  d'utilisation que l'administrateur est responsable du contenu qu'il
  télécharge.

**Pour la validation :**

- Un **serveur de démonstration** (qBittorrent, Prowlarr et Plex de test),
  rempli uniquement de **contenu libre** (distributions Linux, films du
  domaine public ou sous licence Creative Commons), et un compte de démo
  fourni dans les notes de revue.
- Notes de revue claires : ce que fait l'app, qu'elle ne fournit aucun
  contenu, comment tester.
- Politique de confidentialité publiée (données stockées sur Cloudflare,
  identifiants chiffrés, aucune revente) ; questionnaire « Data safety » de
  Google et « App Privacy » d'Apple remplis en conséquence.
- **Nom public : HeyWatchlist** (décidé le 2026-11-25). Aucun « Torrent »
  ni marque tierce (Plex, Netflix…) dans le nom, l'icône ou le sous-titre ;
  « compatible avec Plex » est permis dans la description. Le dépôt garde le
  nom `hey-torrent-native`. Réserver le nom sur App Store Connect et la
  Play Console dès la création des comptes.

**Plans B si un store refuse :**

- iPhone : une boutique alternative de l'Union européenne (AltStore PAL,
  etc.), avec une vérification plus légère (notarisation), ou TestFlight
  pour un usage privé.
- Android : APK signé distribué directement (en suivant les règles de
  vérification des développeurs Android qui s'appliquent au moment de la
  sortie).

### 9.5 Notifications push : « Disponible sur Plex » uniquement (décidé)

Une seule notification en v1 : **un film ou un épisode que vous avez demandé
vient d'arriver sur Plex**. Elle part vers **chaque personne rattachée à la
demande**. Pas de push « téléchargement terminé ».

Chemin prévu : Plex (Docker sur Unraid) → Worker Cloudflare → APNs (iPhone) /
FCM (Android).

- Le Worker garde les jetons d'appareil dans D1.
- L'administrateur reçoit dans l'app **l'URL de webhook de son serveur**
  (avec un jeton secret unique, régénérable), à coller dans Plex et dans
  Tautulli.
- À la réception d'un nouvel ajout Plex, le Worker retrouve le média (via
  l'identifiant TMDB/IMDb), cherche la demande correspondante, la passe en
  `disponible`, puis envoie la push à toutes les personnes qui l'attendent. Un média déjà notifié ne l'est pas deux
  fois.
- Clés APNs et FCM en secrets Workers. Les push Android passent par FCM, donc
  par un **projet Firebase** (gratuit) : ajouté par le propriétaire, la clé de
  service FCM est déjà dans le secret `FCM_SERVICE_ACCOUNT` du Worker
  `heywatchlist-api` ; l'envoi des push se code au jalon 6. Pour l'APK admin
  privé, ntfy auto-hébergé serait une alternative.

**Comment le Worker apprend un nouvel ajout (décidé) :** le propriétaire a
un **Plex Pass** et **Tautulli**.

- **Source principale : webhook Plex** (`library.new`) vers le Worker,
  instantané.
- **Source de secours : webhook Tautulli** (« Recently Added ») vers le même
  Worker. Le webhook Plex `library.new` est parfois capricieux ; Tautulli
  rattrape les ajouts manqués.
- Le Worker **dédoublonne** (même média reçu des deux sources = une seule
  push), grâce à une table des médias déjà notifiés dans D1.
- Le jeton secret de l'URL est vérifié à chaque appel ; une URL inconnue ou
  révoquée est refusée sans détail.
- Le webhook Plex demande un Plex Pass : un administrateur qui n'en a pas
  peut se contenter de Tautulli.

### 9.6 Retenu par défaut, à confirmer par le propriétaire

Propositions du plan (`docs/PLAN.md`), non contredites jusqu'ici :

1. ~~**Connexion**~~ : **décidé le 2026-11-25** (jalon 2) : Apple sur iPhone,
   Google sur Android et sur iPhone ; même compte pour la même adresse e-mail
   vérifiée.
2. **« Sur Plex » pour les invités** : table `library` dans D1, remplie par
   les webhooks et par une synchronisation complète chaque nuit (Cron Trigger
   du Worker vers le Plex de l'administrateur, en HTTPS).
3. **Dépôt privé** : environ 200 minutes de build iPhone gratuites par mois
   sur GitHub Actions ; le job iOS ne se lance que si `core/`, `ios/`,
   `scripts/` ou les fichiers Cargo changent.
4. **APK admin** : à vérifier au jalon 5 face aux règles Google de
   vérification des développeurs pour les APK installés hors Play Store.
5. ~~**Backend en TypeScript**~~ : **décidé le 2026-11-25** (jalon 2).

---

## 10. Glossaire

- **Cœur** : la bibliothèque Rust dans `/core`.
- **UniFFI** : outil de Mozilla qui génère les liaisons Swift et Kotlin.
- **Torznab** : format d'API standard pour interroger un indexeur.
- **Prowlarr** : gestionnaire d'indexeurs auto-hébergé, qui expose une API
  Torznab ; configuré par l'administrateur sur son propre serveur.
- **Scoring** : note attribuée à un torrent pour le classer.
- **Demande** : un média voulu par au moins un utilisateur, que
  l'administrateur traite.
- **Administrateur** : le propriétaire du serveur Plex ; seul à voir les
  sources, les torrents et qBittorrent.
- **Catégorie** : étiquette qBittorrent (`films`, `series`) qui décide du
  dossier de destination.
- **Worker** : petit programme serverless exécuté par Cloudflare.
- **D1** : base SQLite hébergée par Cloudflare.
- **APNs / FCM** : services de notifications push d'Apple et de Google.
