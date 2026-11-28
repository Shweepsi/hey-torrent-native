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

- **Connexion Apple et Google, accès validé par l'administrateur**
  (2026-11-27, choix du propriétaire : plus de code d'invitation). L'app est
  distribuée par un lien App Store non répertorié et un test fermé Play : le
  lien sert d'invitation. Comme un lien peut circuler, tout nouveau compte est
  créé « en attente » et ne voit qu'un écran d'attente ; l'administrateur
  l'accepte ou le refuse d'un geste dans l'onglet Accès de l'APK admin, et la
  personne est prévenue par une notification. Le serveur refuse tout le reste
  (catalogue, watchlist, push d'essai) à un compte en attente ou refusé. Une
  adresse peut être acceptée d'avance (proche prévenu, compte de démonstration
  pour la vérification d'Apple). Pas de mot de passe HeyWatchlist, pas de
  Cloudflare Access. Le Worker vérifie le jeton d'identité puis émet son
  propre jeton court et un jeton de rafraîchissement révocable. Le premier
  administrateur saisit le code d'amorçage sur l'écran d'attente de l'APK
  admin. Suppression du compte depuis l'app, y compris en attente.
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

## Décidé au jalon 2 (2026-11-25)

- **Connexion : Apple sur iPhone, Google sur Android et sur iPhone.** Pas
  d'Apple sur Android. Une même adresse e-mail **vérifiée** par Apple ou Google
  retrouve le même compte.
- **Backend en TypeScript** (Worker) ; le Rust reste dans le cœur.
- Google sur iPhone passe par la page web de Google (OAuth avec PKCE, conduit
  par le cœur), sans SDK Google dans l'app. Sur Android, le Credential Manager
  du système.
- L'adresse du Worker n'est pas dans le dépôt : variable GitHub
  `HEYWATCHLIST_API_URL`, inscrite dans les apps à la compilation. Les
  identifiants des clients Google viennent du Worker (`GET /v1/config`).
- Les tables D1 arrivent avec leur jalon : le jalon 2 ne crée que celles des
  comptes.

## Décidé au jalon 3 (2026-11-26)

- **Images : chargeurs natifs.** Le cœur fabrique les URL du CDN d'images de
  TMDB (`image.tmdb.org`, tailles fixées par le cœur) ; `AsyncImage` (iPhone) et
  Coil (Android) les téléchargent. C'est la seule exception à « pas de réseau en
  Swift ou Kotlin » : des images publiques, sans clé ni jeton. Tout le reste
  passe par le cœur et le Worker.
- **Cache du Worker : D1, pas la Cache API**, qui n'a aucun effet sur
  `workers.dev`. Table `tmdb_cache`, durées de vie par type de requête ; la
  recherche n'est jamais gardée.
- **Relais sur liste blanche** (chemins, paramètres, bornes) : jamais un
  proxy TMDB ouvert, la clé de l'app ne doit pas pouvoir être détournée. Langue
  `fr-FR` et région `FR` imposées par le Worker.
- **Hors ligne : SQLite dans le cœur** (`rusqlite`, SQLite embarqué). Une
  entrée de moins de 5 minutes est servie sans réseau ; au-delà, le réseau, et
  si l'échec est réseau, service indisponible ou erreur du serveur, l'entrée
  périmée est servie avec `stale = true` (bandeau discret). Élagage à 30 jours
  et 400 entrées.
- **Genres de séries** : l'interface passe toujours un identifiant de genre
  de film ; le cœur le traduit en genre de série par une table fixe (Action et
  Aventure vers « Action & Adventure », etc.) et renvoie une page vide quand
  il n'y a pas d'équivalent (Horreur, Romance…). Aucune règle de genre dans
  Swift ou Kotlin.
- **Attribution** : « Disponibilités fournies par JustWatch » dans la section
  des plateformes de la fiche, car TMDB l'exige pour ces données. Le logo TMDB
  et sa phrase d'attribution restent pour « À propos » (jalon 7).
- **Confidentialité iOS** : SQLite embarqué appelle `fstat` et `fstatfs` ;
  `PrivacyInfo.xcprivacy` déclare `FileTimestamp` (`C617.1`) et `DiskSpace`.
  **La raison `E174.1` de `DiskSpace` est provisoire** : à confirmer au premier
  envoi TestFlight (courriel ITMS-91053 éventuel).
- Pas de badge « Sur Plex » au jalon 3 : il dépend de la table `library` et du
  point à trancher n°2 ci-dessous, donc il n'est pas codé.

## Décidé au jalon 4 (2026-11-26)

- **Séries** (choix du propriétaire, ancien point n°6) : on demande la série
  entière ou des saisons au choix. Une liste de saisons vide est refusée ;
  `null` veut dire « série entière ». Le choix se modifie depuis la fiche.
- **Une demande par titre** (`requests`, unique sur type et identifiant TMDB).
  Ses demandeurs sont les lignes vivantes de `watchlist_items` : pas de table
  de rattachement séparée. Retirer un titre est une suppression logique
  (`deleted_at`) ; une demande `requested` ou `refused` que plus personne ne
  suit est supprimée, une demande `in_progress` ou `available` reste.
- **Le Worker lit le titre, l'affiche et l'année chez TMDB** (même cache que le
  relais) : le téléphone n'envoie que le type, l'identifiant et les saisons,
  qui sont vérifiées face à la liste de TMDB.
- **Hors ligne** : le cœur garde une copie de la liste et une file d'au plus un
  changement par titre (le dernier geste l'emporte), envoyée dans l'ordre au
  retour du réseau. La dernière écriture reçue par le Worker gagne. Un
  changement refusé par le serveur (titre introuvable, saisons invalides) est
  abandonné ; une panne réseau le garde. Changement de compte : la copie
  locale est effacée.
- **« Déjà disponible » n'est pas codé** : il dépend de la table `library`
  (point n°2), créée au jalon 6.
- Les invités ne voient jamais de torrent ni de source : seuls l'état et le
  motif de refus remontent.

## Décidé au jalon 5 (2026-11-26)

- **Espace admin = activité à part** (`AdminActivity`) de l'édition admin,
  ouverte depuis Réglages. Le Worker revérifie le rôle à chaque appel.
- **Réglages des serveurs** (sources Torznab, qBittorrent, catégories, termes
  exclus) : texte opaque produit par le cœur, chiffré en AES-256-GCM par le
  Worker (`CREDENTIALS_KEY`, jamais remplacée) dans `credentials`, et gardé
  chiffré par le Keystore sur le téléphone pour travailler hors ligne.
  Adresses en HTTPS uniquement, 20 sources au plus. Aucune source n'est
  préconfigurée.
- **qBittorrent** : connexion par identifiant et mot de passe (cookie) ou par
  jeton (`Authorization: Bearer`). Version lue au premier appel : routes
  `stop`/`start` en v5, `pause`/`resume` en v4. Chaque torrent porte les
  étiquettes `heywatchlist` et `hw-<demande>` ; la catégorie (`films` ou
  `series` par défaut) est créée si elle manque.
- **Tri des releases** : port des règles de l'ancien dépôt pour la pertinence
  (titre, année, saison ; seuil 20), puis qualité : langue d'abord (VFF et
  MULTi VFF 40, VF 35, MULTi 32, VFQ 15, VOSTFR −25, inconnue −40), définition
  (2160p 30, 1080p 28, 720p 15), source (Remux 12, BluRay et WEB-DL 10…),
  codec (x265 et AV1 8, x264 5), HDR +3, seeders (−80 sans seeder, sinon
  jusqu'à +20). Les CAM, livres, albums et épisodes isolés sont écartés. Poids
  à ajuster après les premiers essais.
- **Envoi** : le torrent part d'abord dans qBittorrent, puis la demande passe
  « en cours ». Si ce second appel échoue, l'app le dit et propose de
  réessayer l'enregistrement seul, sans renvoyer le torrent.
- **Vérification des développeurs Android** (ancien point n°4) : Google
  l'a annoncée à partir du 30 septembre 2026 dans quelques pays (pas la France), puis partout à
  partir de 2027. L'installation par ADB reste possible pour un APK non
  vérifié, et un compte gratuit « distribution limitée » (20 appareils, sans
  pièce d'identité) suffira pour l'APK admin si l'installation directe est un
  jour bloquée en France.

## Retenu au jalon 6 (2026-11-26, à confirmer par le propriétaire)

- **Une push par arrivée** : film, série, saison ou épisode. Les épisodes d'une
  même saison arrivés à moins de **6 heures** d'écart ne font qu'une push (une
  saison entière téléchargée d'un coup ne sonne pas dix fois).
- Pour une série, seules les personnes qui attendent **cette saison** (ou la
  série entière) sont prévenues ; la demande passe en `disponible` dès le
  premier épisode arrivé.
- **iPhone par APNs directement** (pas de SDK Firebase dans l'app iOS) ;
  **Android par FCM**, Firebase initialisé par l'app avec les valeurs de
  `google-services.json` lues à la compilation, sans le plugin Google Services.
- **Le texte vient des apps** : le serveur envoie le titre du média et une clé
  de traduction (`push_available_movie`…), traduite par `strings.xml` et
  `Localizable.xcstrings`.
- Le jeton secret est dans le chemin de l'URL (Plex ne sait pas envoyer
  d'en-tête) ; il est régénérable depuis l'APK admin.
- Un épisode Plex ne porte que les identifiants de **l'épisode** : le Worker
  retrouve la série par TMDB (`/find`, identifiant TVDB ou IMDb).

## Choisi le 2026-11-27 : rapports de plantage

- **Firebase Crashlytics** dans les deux APK Android (demande du
  propriétaire) : les plantages remontent seuls dans la console Firebase
  (projet de `google-services.json`), piles lisibles grâce à la table R8
  envoyée par la CI. Pas sur iOS pour l'instant (pas de SDK Firebase).
- Le plugin Crashlytics exige le plugin Google Services pour envoyer la table
  R8 : il est désormais appliqué quand les réglages Firebase existent (le
  secret de la CI est écrit en `app/google-services.json`, ignoré par git).
  Cela remplace « sans le plugin Google Services » du jalon 6 ; l'app garde
  son initialisation manuelle, sans effet quand Firebase s'est déjà initialisé.
- En secours, l'app garde le rapport du dernier plantage et l'affiche au
  lancement suivant, à copier (rien n'est envoyé par ce rapport-là).
- À déclarer dans la politique de confidentialité et « Data safety »
  (diagnostics : plantages, modèle d'appareil, version).

## Décidé le 2026-11-27 : distribution privée

- **iPhone : App Store, distribution non répertoriée** ; **Android : Google
  Play, test fermé** sur invitation, sans passage en production. Choix du
  propriétaire. TestFlight interne sert seulement à vérifier un build.
  Détail et étapes : `docs/PUBLICATION.md`.
- L'APK admin n'est jamais envoyé sur Google Play.
- Envoi automatique sur la piste « Test fermé » (`alpha`) par la CI, sur un
  tag `v*` ou à la demande, avec un script Node sans dépendance (API Google
  Play Developer v3) plutôt que fastlane : pas de Ruby sur la machine Linux.
- Politique de confidentialité et page de suppression du compte servies par le
  Worker (`/confidentialite`, `/suppression-du-compte`) : pas d'hébergement à
  part. Lien dans les Réglages et mention TMDB sur les deux plateformes.
- **Décidé par le propriétaire le 2026-11-27** : pour la revue, pas de serveur de démonstration
  (§9.4) ; le relecteur reçoit un compte Google de démonstration accepté
  d'avance (accès validé par l'administrateur, PR #16). L'app publique n'a
  aucun écran qui touche aux serveurs de l'administrateur : un serveur de démo
  ne montrerait rien de plus.

## Corrigé après la relecture du 2026-11-27 (accord du propriétaire le 2026-11-28)

- **Session** : le jeton de rafraîchissement précédent reste accepté 24 h
  après un renouvellement (`REFRESH_GRACE_SECONDS`), pour qu'une réponse perdue
  ne déconnecte plus. Au-delà, il coupe la session comme avant. Android écrit
  la session avec `commit()`.
- **Séries** : une demande de série ne passe « disponible » que quand chaque
  saison voulue est sur Plex en entier (épisodes déjà diffusés d'après TMDB ;
  « toute la série » = saisons commencées, sans les spéciaux). Ajouter une
  saison absente à une série disponible la remet « demandée ». L'administrateur
  a un bouton « Déjà sur Plex » pour ce que les webhooks n'ont pas vu.
- **APK admin** : plus jamais en artefact public ; envoyé par Firebase App
  Distribution (`docs/CI.md`, « APK admin privé »).
- **Journaux Cloudflare** : pas de journal d'appel par requête (l'URL des
  webhooks contient leur jeton).

## Par défaut, à confirmer par le propriétaire

2. « Sur Plex » pour les invités : table `library` dans D1, remplie par les
   webhooks et par une synchronisation complète chaque nuit.
3. Dépôt privé (budget de minutes macOS à surveiller).
