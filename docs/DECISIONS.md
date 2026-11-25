# Décisions

Décisions prises par le propriétaire le **2026-11-25**, sauf mention contraire.
Le détail et les justifications sont dans `context_hey-torrent.md` (§9 surtout).
Un agent ne code pas une option « ouverte » ou « par défaut » sans que le
propriétaire l'ait choisie ; il peut préparer le code pour que le choix reste
facile.

## Produit

- **Nom public : HeyWatchlist.** Le dépôt garde le nom `hey-torrent-native`.
  Aucun « Torrent » ni marque tierce dans le nom, l'icône ou le sous-titre.
- **Un Overseerr natif, centré sur la découverte TMDB.** Les invités
  parcourent TMDB et ajoutent à leur watchlist ; chaque ajout est une demande
  que l'administrateur traite. Pas de Sonarr ni Radarr : le cœur fait lui-même
  la recherche Torznab, le scoring et l'envoi à qBittorrent (à partir du
  jalon 5, administrateur seulement).
- **Priorités, dans l'ordre** : découverte, puis watchlist, puis espace admin.
  Trois onglets : **Découvrir · Watchlist · Réglages**.
- **Langue : français uniquement**, textes dans les fichiers de ressources
  (`strings.xml`, `Localizable.xcstrings`).
- **Hors périmètre** : widgets, Live Activities, Dynamic Island, montre,
  tablette optimisée, Android TV.

## Architecture

- **Cœur Rust partagé** (`core/`) : toute la logique. Interfaces natives
  **SwiftUI** et **Jetpack Compose** : elles n'affichent que.
- **Pont : Mozilla UniFFI en macros** (`#[uniffi::export]`), pas de fichier
  UDL. Les liaisons générées ne sont jamais versionnées.
- **Le cœur ne produit aucun texte affiché** : il renvoie des types d'erreur
  que chaque interface traduit en français.
- **Le cœur est tolérant** : une entrée illisible est ignorée, jamais fatale
  pour toute une liste. Pas de `unwrap`, `expect` ni `panic` (clippy l'impose),
  et jamais `panic = "abort"` en release : UniFFI s'appuie sur le déroulement
  de la pile pour attraper les paniques.
- **Backend sur Cloudflare** (Workers + D1), pas Supabase. Le cœur ne parle
  qu'à l'API du Worker, jamais à D1. Le Worker se déploie sous le nom
  `heywatchlist-api` (un autre nom perdrait son secret FCM).
- **Version unique** : `[workspace.package] version` du `Cargo.toml` racine ;
  Android (`versionName`) et iOS (`MARKETING_VERSION`) la lisent, personne ne
  la duplique. Toute PR qui change l'app la monte et ajoute son entrée en tête
  de `CHANGELOG.md`.

## Compte, synchronisation, identifiants

- **Connexion Apple et Google, sur invitation.** Pas de mot de passe
  HeyWatchlist, pas de Cloudflare Access. Le Worker vérifie le jeton d'identité
  puis émet son propre jeton court et un jeton de rafraîchissement révocable.
  Le premier compte, créé au déploiement, est l'administrateur. Suppression du
  compte depuis l'app.
- **Watchlist et demandes synchronisées, la dernière modification gagne**
  (champ par champ, horodatage posé par le Worker, suppression logique). D1 est
  la source de vérité ; SQLite sur le téléphone sert de cache hors ligne avec
  file d'attente des modifications.
- **Identifiants des serveurs : administrateur seulement, chiffrés sur
  Cloudflare** (AES-256-GCM, clé en secret Workers), avec copie locale dans le
  Keychain ou le Keystore. Un utilisateur n'en reçoit jamais aucun. La clé TMDB
  est celle de l'app, en secret Workers, relayée par le Worker.
- **Secrets sur le téléphone** : Keychain (iOS), Keystore via DataStore chiffré
  (Android). Le cœur ne persiste jamais un secret en clair.

## Notifications

- **Un seul push en v1 : « Disponible sur Plex »**, envoyé à chaque personne
  rattachée à la demande. Pas de push « téléchargement terminé ».
- **Source principale : webhook Plex** (`library.new`) ; **source de secours :
  webhook Tautulli**. Le Worker dédoublonne (une seule push par média).
- Chemin : Plex, puis Worker Cloudflare, puis APNs (iPhone) ou FCM (Android).
  Firebase (projet `heywatchlist-60448`) ne sert qu'à FCM : le compte de
  service est déjà rangé dans le secret `FCM_SERVICE_ACCOUNT` du Worker
  `heywatchlist-api` ; l'envoi des push se code au jalon 6.

## Deux apps, voie honnête vers les stores

- **HeyWatchlist** (App Store, Play Store) : utilisateurs seulement.
  **HeyWatchlist Admin** : APK Android privé, jamais publié. Un seul projet
  Android, deux variantes Gradle (`public`, `admin`).
- **Le code d'administration n'existe que dans l'édition admin** : sous
  `android/app/src/admin/` côté Android, derrière la fonctionnalité Cargo
  `admin` (désactivée par défaut) côté cœur. L'app publique n'en contient aucun
  symbole. Pas d'édition admin sur iOS.
- **Identifiants d'app (définitifs après publication)** :
  `io.github.shweepsi.heywatchlist` pour l'app publique Android et le bundle
  iOS, `io.github.shweepsi.heywatchlist.admin` pour l'édition admin
  (`applicationIdSuffix ".admin"`). Les deux apps Android sont déclarées dans
  le projet Firebase du propriétaire.
- **Aucun tracker nommé, intégré ou préconfiguré** : ni dans le code, ni dans
  les textes, ni dans la fiche store, les captures ou la démo.
  L'administrateur ajoute ses propres sources Torznab. Aucun scraping HTML.
- **Aucune fonction cachée** pendant la revue ; serveur de démonstration avec
  du contenu libre uniquement ; politique de confidentialité publiée.
- **Plans B** : TestFlight ou boutique alternative de l'Union européenne
  (iPhone) ; APK signé distribué directement (Android).

## Plateformes et compilation

- **iOS 26 minimum** (Liquid Glass natif partout, sans repli) ; **Android 12
  (API 31) minimum** (flou natif), cible la dernière API.
- Architectures : `aarch64-apple-ios`, `aarch64-apple-ios-sim`,
  `aarch64-linux-android`, `x86_64-linux-android`.
- **Pas de Mac : GitHub Actions compile l'app iPhone** et l'envoie à
  TestFlight. Le projet Xcode est **généré par XcodeGen** (`ios/project.yml`),
  jamais versionné. Un essai sur iPhone passe par la CI et TestFlight.
- **Dépôt privé** : le job iOS (minutes macOS, facturées dix fois) ne se lance
  que si `core/`, `ios/`, `scripts/` ou les fichiers Cargo changent.
- **Tests** : les tests n'appellent jamais les vrais services (TMDB, Plex,
  qBittorrent, sources Torznab) ; réponses simulées avec `wiremock`.

## Apparence

- **Verre dépoli moderne**, thème sur mesure, **pas de Material 3** (voir
  `docs/DESIGN.md`). iPhone : Liquid Glass natif. Android : `HeyTheme` sur
  Compose Foundation, flou par Haze.

## Par défaut, à confirmer par le propriétaire

1. Connexion : Apple sur iPhone, Google sur Android, Google aussi sur iPhone
   (Apple reste alors obligatoire à côté) ; un invité garde son compte avec la
   même adresse e-mail.
2. « Sur Plex » pour les invités : table `library` dans D1, remplie par les
   webhooks et par une synchronisation complète chaque nuit.
3. Dépôt privé (budget de minutes macOS à surveiller).
4. APK admin : à vérifier au jalon 5 face aux règles Google de vérification des
   développeurs pour les APK installés hors Play Store.
5. Backend en TypeScript (Worker) ; le Rust reste dans le cœur.
6. Séries : demande de la série entière ou de saisons précises, comme
   Overseerr.
