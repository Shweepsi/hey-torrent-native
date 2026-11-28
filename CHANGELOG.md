# Journal des changements

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
numérotation [sémantique](https://semver.org/lang/fr/). La version de l'app est
`[workspace.package] version` du `Cargo.toml` racine. Les entrées les plus
récentes sont en tête.

## [0.13.4] - 2026-11-28

Retours de Loïc sur la 0.13.2.

### Modifié

- Android : boutons en pastille de verre, de la même famille que la barre
  d'onglets. Le bouton principal (« Ajouter à ma watchlist », « Valider ») est
  un verre bleu lumineux, avec reflet et halo ; « Bande-annonce » passe en
  verre légèrement bleuté, en retrait ; les autres restent en verre neutre.
- Android : en-tête des onglets revu. Grand titre identique sur Découvrir,
  Watchlist et Réglages (même marge, même taille, sous-titre estompé) ; la
  barre flottante reprend le titre en petit, centré.
- Android : un seul « Rechercher » en haut de Découvrir. Le bouton loupe de la
  barre n'apparaît qu'une fois le champ de recherche passé sous la barre.
- iPhone : l'onglet Rechercher n'affiche plus « Rechercher » deux fois (titre
  de l'écran et message d'attente).

## [0.13.3] - 2026-11-28

### Modifié

- Découvrir (rangées, grandes images, genres) et les titres similaires d'une
  fiche ne montrent plus que les titres en langue d'origine européenne
  (anglais compris) et l'animation japonaise : ce qui vient d'Asie hors
  animés, presque jamais disponible en français, n'y est plus proposé. La
  recherche reste complète. Une page entièrement écartée est sautée, le
  défilement continue.

## [0.13.2] - 2026-11-28

Corrections d'interface demandées par Loïc.

### Modifié

- Android : barres du haut et d'onglets flottantes, en verre dépoli, au-dessus
  du contenu qui défile dessous (bord à bord) et le floutent. Au repos, la barre
  du haut est transparente ; elle prend son verre et le titre en petit dès que
  le contenu passe dessous. Recherche, genres et fiche ont aussi leur en-tête
  flottant.
- Fiche d'un titre : le fond du titre se fond dans la fiche par un dégradé en
  courbe douce, sans ligne au bas de l'image (Android et iPhone).
- Boutons accentués : aplat bleu plus profond et texte blanc lisible en clair
  comme en sombre, état enfoncé assombri, état inactif neutre (Android).

### Corrigé

- Clignotements : une affiche déjà chargée s'affiche sans refaire son fondu
  (défilement, changement d'onglet, retour d'une fiche), sur Android et iPhone ;
  ouvrir ou quitter une fiche ne passe plus par un écran vide ; le changement
  d'onglet n'enchaîne plus deux fondus.
- L'année sous chaque affiche n'est plus rognée par le coin arrondi.

### Retiré

- « Où regarder » : la fiche ne propose plus les plateformes de streaming (le
  Worker ne les demande plus à TMDB).
- Le bouton « Envoyer une notification d'essai » de Réglages, et sa route
  `POST /v1/notifications/test`.
- Les épisodes spéciaux (saison 0) ne sont plus proposés dans le choix des
  saisons d'une série.

## [0.13.1] - 2026-11-28

Corrections de l'audit du cœur du 2026-11-28 (points 1 à 9).

### Corrigé

- Plex : un titre arrivé sur Plex avant d'être demandé passe bien
  « disponible » à la lecture de la bibliothèque (l'arrivée « déjà vue »
  n'empêche plus que la notification). Une demande remise en attente à la main
  depuis « disponible » n'y repasse pas toute seule.
- Recherche automatique : un échec de Claude sur une demande ne bloque plus
  les suivantes ; le passage ne s'arrête qu'après deux échecs de suite ou une
  clé refusée.
- « À traiter » : une demande dont la recherche n'a pas pu se faire (Claude en
  échec, toutes les sources en échec depuis Cloudflare, clé Anthropic refusée)
  y apparaît, au lieu d'attendre l'alerte des 3 semaines.
- Des règles changées font rejuger par Claude les releases déjà écartées.
- Séries : Claude reçoit les saisons voulues (ou « toutes ») ; quand elles
  changent, la release trouvée est oubliée et la recherche reprend.
- Recommandation : l'app attend la réponse 90 s (20 s avant) ; le Worker laisse
  à Claude deux essais de 40 s.
- Catégorie retenue à l'envoi : une seule règle pour l'envoi et les réglages.
  Une sous-catégorie (`Films/4K`) ou un nom avec un point est maintenant
  retenu ; `..` et les segments vides sont refusés.
- Watchlist : un changement qui reçoit une réponse imprévue (limitation,
  intermédiaire) reste en file au lieu d'être abandonné, sans bloquer les
  suivants.

### Modifié

- Recherche automatique : les essais sans résultat s'espacent (6 h, puis 24 h
  après 4 essais, puis 3 jours après 8) pour ménager les sources.
- Migration `0008_recherche_fiable.sql`.

## [0.13.0] - 2026-11-28

Demandes de Loïc sur l'APK admin.

### Ajouté

- Serveurs, section Données : « Vider les demandes », après confirmation.
  Efface toutes les demandes, les watchlists qui s'y rattachent et les traces
  de notification (`POST /v1/admin/requests/clear`, `{"confirm": true}`). Les
  comptes, les accès, les réglages et la bibliothèque Plex ne sont pas touchés.

### Modifié

- Envoi d'une release : la catégorie choisie devient celle présélectionnée au
  prochain envoi du même type (retenue par le cœur dans les réglages).
- Serveurs : « Catégories et filtres » disparaît. Les deux champs de catégorie
  sont retirés ; « Termes exclus » passe dans la section Recherche.

## [0.12.0] - 2026-11-28

Retours de Loïc sur l'APK admin.

### Ajouté

- Envoi d'une release : la catégorie se choisit parmi celles qui existent déjà
  dans qBittorrent (`GET /api/v2/torrents/categories`). Celle de Serveurs pour
  le type (film ou série) est présélectionnée ; si qBittorrent n'en a aucune,
  elle est créée à l'envoi.
- Serveurs : section Plex, avec l'état du lien (dernier appel reçu) et
  l'adresse des webhooks, déplacée depuis Réglages.
- Claude (API Anthropic, modèle `claude-opus-5-5`) : après une recherche,
  la release qui respecte le mieux les règles de l'administrateur passe en
  tête avec une raison courte. Un appel par recherche ; l'envoi reste manuel.
  Clé d'API et règles (une phrase libre) dans Serveurs, chiffrées avec les
  autres réglages.
- Recherche automatique par le Worker (déclencheur horaire) : chaque demande
  en attente est cherchée au plus toutes les 6 h (8 par passage), les films
  pas encore sortis attendent leur date. Une demande n'est « À traiter »
  qu'une fois qu'une release convient ; les invités voient toujours
  « Demandé ». Sans changement des résultats, Claude n'est pas rappelé.
- Une notification à l'administrateur quand une demande reste 3 semaines
  sans release convenable (une fois par demande).
- Serveurs : les sources qui échouent depuis Cloudflare sont signalées ;
  elles restent cherchables depuis le téléphone.
- Plex : connexion directe facultative (adresse HTTPS + jeton). Le Worker lit
  la bibliothèque toutes les 6 h et passe « disponible » ce qui y est déjà
  (séries : saisons complètes), avec les push habituelles.
- Migration `0007_recherche_auto.sql`. Dépendance `@anthropic-ai/sdk` 1.14.0
  (backend).

### Modifié

- « Déjà sur Plex » demande une confirmation, et une demande passée
  « disponible » peut être remise en attente (route `reopen` acceptée depuis
  `available`).
- Suivi : les téléchargements portant l'étiquette `heywatchlist` sont listés
  quelle que soit leur catégorie.

## [0.11.0] - 2026-11-28

Deuxième passe sur le design Android, d'après les captures de l'APK admin.

### Modifié

- Android : clair ou sombre selon le réglage du téléphone, sur tous les écrans,
  fiche d'un titre comprise (elle était toujours sombre).
- Android : affichage bord à bord. Les barres système sont transparentes, sans
  le voile gris ou blanc de la barre à trois boutons.
- Serveurs : une ligne par service avec son état (testé seul à l'ouverture),
  et une feuille qui monte du bas pour modifier ses champs, le tester et
  enregistrer. Fini le long formulaire.
- Releases : la qualité en pastilles et les chiffres d'abord, le nom brut en
  petit dessous.
- Boutons : « Retirer » et « Annuler » deviennent des liens discrets ; le bleu
  plein reste à l'action principale. Le choix segmenté (Identifiant / Jeton,
  À traiter / Toutes) est une pastille de verre, plus compacte.
- Champs de saisie plus fins (texte courant au lieu de titre).
- Résultats des tests de connexion et enregistrement : vert franc avec une
  coche, rouge avec une icône d'alerte (au lieu d'un lilas pâle).
- Confirmations admin : feuille en verre qui monte du bas. L'envoi d'une
  release montre la qualité en pastilles, la taille et les sources, puis le nom
  brut, qui se coupe aux points et plus au milieu des mots.
- Écran de connexion : vraies affiches en tendance (route publique
  `GET /v1/posters` du Worker), voile plus marqué sous le titre. Sans réseau,
  le mur dessiné reste.

## [0.10.0] - 2026-11-28

Corrections de la relecture du code du 2026-11-27.

### Ajouté

- APK admin : bouton « Déjà sur Plex » sur une demande, pour un titre arrivé
  avant les webhooks ou une arrivée qu'ils n'ont pas vue (personne n'est
  prévenu). Route `POST /v1/admin/requests/{id}/available`.
- CI Android : l'APK admin signé part par Firebase App Distribution (secrets
  `FIREBASE_DISTRIBUTION_KEY` et `FIREBASE_TESTERS`, posés par hey-kit) au lieu
  d'un artefact téléchargeable par tous.

### Corrigé

- Déconnexions surprises : une réponse de renouvellement perdue (réseau coupé,
  app fermée) ne coupe plus la session ; l'ancien jeton reste accepté 24 h.
  Android écrit la session de façon synchrone.
- Une série ne passe plus « disponible » au premier épisode arrivé sur Plex :
  il faut toutes les saisons voulues, en entier. Une saison absente demandée
  plus tard (nouvelle saison) remet la demande dans la file de l'administrateur.
- L'APK admin de debug n'est plus publié en artefact (dépôt public).
- Le Worker ne garde plus de journal d'appel par requête, qui contenait le
  jeton secret des URL de webhook.

## [0.9.0] - 2026-11-27

Passe de design sur Android, dans le style verre dépoli (pas de Material 3).

### Ajouté

- Accueil et attente : mur d'affiches stylisées qui défile lentement derrière
  le verre (dessiné, aucune image réelle), marque de l'app, trois promesses de
  l'app ; sur l'attente, les étapes de validation et une horloge qui respire.
- Fiche : fond de page tiré de l'affiche, très flouté (calculé une fois, il ne
  défile pas) ; fond du titre en parallaxe qui s'efface dans la page ; affiche
  détourée avec une ombre.
- Pastilles d'état (demandée, en cours, disponible, refusée) avec icône, texte
  et teinte, dans la watchlist, la fiche et l'espace admin. Jetons `success`,
  `warning` et `glassHighlight` dans `HeyTheme`, en sombre et en clair.
- Barre d'onglets flottante à icônes (boussole, marque-page, curseurs) avec une
  pastille qui glisse vers l'onglet choisi ; même barre dans l'espace admin.
- Mouvement : fondu entre l'accueil, l'attente et les onglets, fondu entre
  onglets, écran ouvert qui glisse depuis la droite, éléments touchables qui
  s'enfoncent (ressort), carrousel dont les affiches voisines reculent. Tout se
  fige quand les animations sont supprimées dans l'accessibilité.
- Espace admin : demandes avec initiales des personnes qui suivent, comptes
  avec initiale et état de l'accès.
- Captures d'écran rendues par Robolectric sur la CI (job « Captures d'écran »,
  artefact `captures-android`), avec des données et des images inventées :
  `./gradlew testPublicDebugUnitTest testAdminDebugUnitTest -Pscreenshots`.

### Modifié

- Verre : filet qui s'éclaircit vers le haut (reflet). Bouton principal en
  léger dégradé (texte au moins 4,5:1 partout).
- Watchlist : lignes en cartes translucides et nombre de titres sous le titre.
- Build : les dépendances se téléchargent d'abord par le miroir Google de Maven
  Central, Maven Central restant en secours (il refusait souvent la CI, erreur
  429), y compris pour buildSrc et pour les images d'Android de Robolectric.
- CI : « CI Android » lancé à la main accepte `captures_ref` (captures d'un
  autre commit, pour un avant/après) et écrit les captures dans le journal.

## [0.8.0] - 2026-11-27

Plus de code d'invitation : le lien de l'app sert d'invitation, et chaque
nouveau compte attend que l'administrateur valide son accès.

### Ajouté

- Worker : comptes `pending`, `active` ou `refused` (migration
  `0006_acces.sql`, les comptes existants restent actifs). Un compte en attente
  ou refusé n'a droit qu'à son profil, à la suppression de son compte et à
  l'enregistrement de son appareil ; tout le reste répond `access_pending` ou
  `access_refused`. Notification à l'administrateur à chaque nouvelle demande,
  et à la personne quand elle est acceptée. Routes `/v1/admin/members`
  (accepter, refuser, retirer l'accès) et `/v1/admin/allowed-emails` (adresses
  acceptées d'avance, par exemple le compte de démonstration pour Apple).
  `POST /v1/auth/bootstrap` pour le premier administrateur. Tests Vitest.
- Cœur : `AccessStatus` dans `UserProfile` ; derrière `admin`,
  `Account::claim_admin` et, dans `AdminSpace`, `members`, `approve_member`,
  `refuse_member`, `allowed_emails`, `allow_email`, `remove_allowed_email`.
  Tests `wiremock`.
- Android et iPhone : écran d'attente (vérifier, se déconnecter, retirer sa
  demande), relu au retour dans l'app et à la notification d'acceptation ;
  écran « Accès non accordé ».
- APK admin : onglet Accès (demandes à accepter ou refuser d'un geste,
  comptes dont on peut retirer l'accès, adresses acceptées d'avance) et code
  administrateur sur l'écran d'attente.

### Retiré

- Codes d'invitation : écran de saisie, table `invitations`, routes
  `/v1/invitations` et `/v1/admin/invitations`, erreurs `InvitationRequired` et
  `InvitationInvalid` du cœur.

## [0.7.0] - 2026-11-27

Préparation des stores : App Store en distribution non répertoriée sur
iPhone, test fermé Google Play sur Android (`docs/PUBLICATION.md`).

### Ajouté

- Worker : politique de confidentialité (`GET /confidentialite`) et page de
  suppression du compte (`GET /suppression-du-compte`), en HTML, avec le
  contact `PRIVACY_CONTACT` (facultatif). Tests Vitest.
- Cœur : `privacy_policy_url`, l'adresse de la politique construite depuis
  celle du serveur.
- Réglages, iPhone et Android : bouton « Politique de confidentialité » et
  mention de TMDB.
- CI Android : sur un tag `v*`, ou à la demande, l'AAB public part sur la piste
  « Test fermé » de Google Play (secret `PLAY_SERVICE_ACCOUNT_JSON`), notes de
  version tirées de ce journal. Script Node sans dépendance
  (`.github/scripts/play-upload.mjs`) et ses tests.
- `docs/PUBLICATION.md` : étapes de publication, textes des fiches, réponses
  « Confidentialité de l'app » et « Sécurité des données », notes de revue.

## [0.6.1] - 2026-11-27

### Corrigé

- Android : « Se connecter avec Google » ne reste plus muet après le choix du
  compte. Le refus des services Google (code 16, app non déclarée chez Google)
  affiche « La connexion avec Google n’est pas disponible pour le moment », et
  une fenêtre fermée affiche « Connexion annulée ».
- Android : l'app ne plante plus juste après la connexion. La lecture de la
  version du cœur, pour les Réglages, s'appelait elle-même sans fin
  (`StackOverflowError`).

### Ajouté

- CI Android : les empreintes SHA-1 et SHA-256 du certificat de signature des
  APK admin et public s'affichent dans le résumé du job de release, pour les
  déclarer dans la console Google.
- Android : après un plantage, l'app affiche au lancement suivant le rapport
  (pile de l'exception, ou raison d'arrêt donnée par Android pour un plantage
  natif ou une app figée), à copier. L'app reste en pause tant qu'il est
  affiché. Rien n'est envoyé.
- Android : Firebase Crashlytics (BoM 35.2.0 : `firebase-crashlytics` 21.1.0,
  plugin 3.0.8) dans les deux APK. Les plantages remontent dans la console
  Firebase ; la CI envoie la table R8 pour des piles lisibles. Le plugin
  Google Services (4.6.0) est appliqué quand les réglages Firebase existent.

### Modifié

- Android : une erreur de Firebase en demandant le jeton des notifications ne
  fait plus planter l'app ; les notifications sont simplement coupées.

## [0.6.0] - 2026-11-26

Jalon 6 : notifications « Disponible sur Plex ». Quand un film ou un épisode
demandé arrive sur Plex, chaque personne qui l'attend reçoit une notification
sur iPhone (APNs) ou Android (FCM).

### Ajouté

- Worker : webhooks `POST /v1/hooks/plex/<jeton>` (Plex Pass, `library.new`)
  et `POST /v1/hooks/tautulli/<jeton>` (secours), URL secrète créée et
  régénérée par l'administrateur (`/v1/notifications/webhook`), dédoublonnage
  entre les deux sources, épisodes d'une même saison regroupés sur 6 heures,
  série retrouvée par TMDB (`find`) depuis les identifiants d'un épisode.
  Passage de la demande en `available`, table `library`, push aux personnes
  qui attendent ce titre (et cette saison). Migration `0004_notifications.sql`
  (`devices`, `library`, `notified`, `webhook_tokens`). Tests Vitest.
- Worker : envoi APNs (jeton ES256) et FCM HTTP v1 (compte de service), jetons
  d'appareil enregistrés par session (`PUT`/`DELETE /v1/devices`), jetons
  périmés oubliés, push d'essai (`POST /v1/notifications/test`). Nouveaux
  secrets `APNS_KEY_ID` et `APNS_PRIVATE_KEY`, posés par l'étape 7/7 de
  `configurer-backend.mjs`.
- Cœur : `register_push_device`, `unregister_push_device`, `send_test_push` ;
  `webhook_status` et `regenerate_webhook` derrière `admin`. Tests `wiremock`.
- Android : Firebase Messaging, initialisé sans le plugin Google Services
  (valeurs de `google-services.json` lues à la compilation, secret
  `GOOGLE_SERVICES_JSON`) ; permission demandée après la connexion, canal
  « Disponible sur Plex », carte Notifications dans Réglages (notification
  d'essai). APK admin : carte « Notifications Plex » avec les URL à copier.
- iPhone : APNs, permission demandée après la connexion, notifications
  affichées app ouverte, carte Notifications dans Réglages. La CI ajoute la
  capacité « Notifications push » à l'identifiant d'app.

## [0.5.0] - 2026-11-26

Jalon 5 : l'espace admin, dans l'APK admin seulement. L'administrateur voit
toutes les demandes, cherche dans ses sources Torznab (Prowlarr), choisit une
release notée et l'envoie à son qBittorrent ; il peut refuser avec un motif,
suivre les téléchargements, régler ses serveurs et gérer les invitations.

### Ajouté

- Worker : routes `/v1/admin/requests` (liste, `start`, `refuse`, `reopen`),
  `/v1/admin/settings` (réglages chiffrés, AES-256-GCM) et
  `/v1/admin/invitations` (liste, révocation) ; erreur `conflict` (409) ;
  migration `0005_admin.sql`. Tests Vitest.
- Cœur, fonctionnalité `admin` : objet `AdminSpace`, recherche Torznab en
  parallèle (6 s par source, repli sur la recherche simple, résultats au fil
  de l'eau), lecture des noms de release, tri des releases francophones,
  client qBittorrent v4 et v5. Tests `wiremock` et jeu de cas de tri.
- Android, édition admin : `AdminActivity` (Demandes, fiche, Suivi, Serveurs,
  Invitations), tests des ViewModels dans `src/testAdmin/`, lancés par la CI.
- `configurer-backend.mjs` pose le secret `CREDENTIALS_KEY`.

### Modifié

- Android : la création d'invitations passe de Réglages à l'espace admin ;
  `KeystoreSessionStore` accepte un fichier et une clé propres ;
  `HeyTextField` peut masquer sa saisie.

## [0.4.0] - 2026-11-26

Jalon 4 : la watchlist. Depuis la fiche d'un film ou d'une série, on l'ajoute
à sa watchlist, ce qui crée la demande ou rattache la personne à celle qui
existe déjà. L'onglet Watchlist montre l'état de chaque demande (demandée, en
cours, disponible, refusée). Une série se demande entière ou par saisons.

### Ajouté

- Worker : routes `GET /v1/watchlist`, `PUT` et `DELETE
  /v1/watchlist/{movie|tv}/{id}`, tables `requests` et `watchlist_items`
  (migration `0003_watchlist.sql`), une demande par titre, titre et affiche lus
  chez TMDB par le Worker, saisons vérifiées, suppression des demandes que plus
  personne ne suit. Tests Vitest.
- Cœur : objet `Watchlist` (liste, ajout, retrait, rafraîchissement), copie
  locale SQLite et file d'attente hors ligne d'un changement par titre, envoyée
  au retour du réseau ; erreurs typées `WatchlistError`. Tests `wiremock`.
- Android et iPhone : bouton « Ajouter à ma watchlist » sur la fiche, choix
  « série entière » ou saisons, état de la demande, retrait ; onglet Watchlist
  avec tirer pour rafraîchir et bandeau hors ligne.

### Modifié

- Cœur : `ApiFailure::Rejected` pour les refus `invalid_request` et
  `forbidden` du Worker.
- Worker : le relais TMDB expose sa lecture en cache (`cachedTmdb`) pour la
  watchlist.

## [0.3.0] - 2026-11-26

Jalon 3 : Découvrir. L'onglet Découvrir parcourt le catalogue TMDB en
français sur iPhone et Android : carrousel, rangées, genres, recherche
instantanée et fiche détail. Aucun badge « Sur Plex » ni ajout à la watchlist
pour l'instant (jalons 4 et 6).

### Ajouté

- Worker : relais `GET /v1/tmdb/<chemin>` du catalogue TMDB, derrière le jeton
  d'accès, sur une liste blanche stricte de chemins et de paramètres (langue
  `fr-FR`, région `FR` imposées), avec cache dans D1 (table `tmdb_cache`,
  migration `0002_tmdb_cache.sql` ; la Cache API ne fonctionne pas sur
  `workers.dev`). Nouveau secret `TMDB_API_KEY` (clé v3 ou jeton v4), saisi par
  `configurer-backend.mjs`. Tests Vitest.
- Cœur : objet `Catalog` (étagères, genres, genre, recherche, fiche, saison),
  types sans texte affiché, erreurs typées `CatalogError`, décodage tolérant
  (une entrée illisible est ignorée), URL d'images fabriquées par le cœur,
  cache SQLite hors ligne (`rusqlite`, SQLite embarqué) avec repli sur
  l'entrée périmée quand le réseau manque, correspondance des genres de films
  vers ceux des séries. Tests `wiremock`.
- Android : écrans Découvrir (carrousel, rangées indépendantes avec squelettes
  et « Réessayer », pastilles de genres), Genre, recherche instantanée, fiche
  détail plein écran (bande-annonce, plateformes en France, casting, saisons et
  épisodes, similaires) ; images par Coil 3.3.0.
- iPhone : mêmes écrans en Liquid Glass, recherche dans la bulle séparée
  (`Tab(role: .search)`), fiche avec transition zoom ; images par
  `AsyncImage`.
- Mention « Disponibilités fournies par JustWatch » dans la fiche, comme TMDB
  l'exige pour les plateformes.

### Modifié

- La session expirée détectée par le catalogue ramène à l'accueil, comme celle
  détectée par le compte.
- `PrivacyInfo.xcprivacy` : déclare `FileTimestamp` (`C617.1`) et `DiskSpace`
  (`E174.1`, provisoire) à cause de SQLite embarqué.

## [0.2.0] - 2026-11-25

Jalon 2 : comptes et connexion. On se connecte avec Apple (iPhone) ou Google
(Android et iPhone), sur invitation, et Réglages affiche son prénom.

### Ajouté

- `backend/` : Worker Cloudflare `heywatchlist-api` en TypeScript, base D1
  `heywatchlist` (tables des comptes seulement). Vérification des jetons
  d'identité Apple et Google (signature, émetteur, audience, nonce à usage
  unique), comptes sur invitation, premier compte administrateur par code
  d'amorçage, même compte pour la même adresse e-mail vérifiée, jeton d'accès
  de 15 minutes et jeton de rafraîchissement renouvelé (réutilisation
  détectée), création d'invitations par l'administrateur, suppression du
  compte avec révocation chez Apple. 35 tests Vitest dans `workerd`.
- CI `ci-backend.yml` : TypeScript et tests sur chaque PR ; sur `main`,
  migrations D1 et déploiement si les réglages Cloudflare existent.
- `backend/scripts/configurer-backend.mjs` : réglage en une commande sur le PC
  du propriétaire (jeton Cloudflare, secrets du Worker, secret et variables
  GitHub), sans afficher de secret.
- Cœur : objet `Account` (connexion, profil, rafraîchissement automatique et
  sérialisé des jetons, déconnexion, suppression), session gardée par l'app
  (Keychain, Keystore) via `SessionStore`, jeton d'accès en mémoire seulement ;
  connexion Google sur iPhone par OAuth avec PKCE ; client HTTPS `reqwest` +
  `rustls` ; `create_invitation` dans l'édition admin. Tests `wiremock`.
- Android : accueil avec Google (Credential Manager), saisie du code
  d'invitation, onglets Découvrir · Watchlist · Réglages, Réglages avec prénom,
  déconnexion et suppression du compte ; carte « Inviter quelqu'un » dans
  l'APK admin.
- iPhone : accueil avec « Se connecter avec Apple » et Google, mêmes écrans ;
  suppression d'un compte Apple précédée d'une nouvelle autorisation Apple.
- CI iOS : capacité « Sign in with Apple » ajoutée à l'identifiant d'app et
  profil régénéré si besoin ; adresse du serveur lue dans la variable GitHub
  `HEYWATCHLIST_API_URL`, comme sur Android.

### Retiré

- L'écran « pong » du jalon 1 et la carte d'état de l'édition admin.

## [0.1.1] - 2026-11-25

### Modifié

- CI iOS : compilation avec Xcode 27 et le SDK iOS 27 (image `xcode-27`), pour
  lever l'avertissement Apple ITMS-90725 (SDK iOS 27 exigé à partir d'avril
  2027). Le simulateur des tests est choisi parmi ceux de la machine au lieu
  d'un nom figé. La cible de déploiement reste iOS 26.

## [0.1.0] - 2026-11-25

Jalon 1 : fondations et chaîne de compilation. Une app vide mais installable,
qui n'affiche que ce que renvoie le cœur Rust. Aucune fonctionnalité utilisateur
(ni TMDB, ni connexion, ni backend).

### Ajouté

- Espace de travail Rust (`Cargo.toml`) : version de l'app en source unique,
  lints clippy `unwrap_used`, `expect_used` et `panic` refusés, `unsafe`
  interdit, profil de release réglé pour le mobile (sans `panic = "abort"`).
- Cœur `heywatchlist-core` exposé par UniFFI (macros, sans fichier UDL) :
  `core_version()`, `ping()` (« pong »), `ping_async()` sur `tokio`,
  `has_admin()` et l'énumération d'erreurs `CoreError`.
- Fonctionnalité Cargo `admin` (désactivée par défaut) : ajoute
  `admin_ping()` (« admin-pong »). Seule l'édition admin d'Android la compile.
- Outil `tools/uniffi-bindgen` et scripts `scripts/generate-bindings.sh`,
  `scripts/build-android-core.sh` (`cargo-ndk`, `arm64-v8a` et `x86_64`, API 31)
  et `scripts/build-ios-xcframework.sh`. Les liaisons générées ne sont pas
  versionnées.
- App Android (Jetpack Compose, Android 12 minimum) : éditions `public`
  (`HeyWatchlist`) et `admin` (`HeyWatchlist Admin`), identifiants
  `io.github.shweepsi.heywatchlist` et `.admin`, premier `HeyTheme` en
  verre dépoli (Haze, sans Material 3), écran d'accueil qui affiche le nom, la
  version du cœur et « pong ». Le code admin est confiné à `src/admin/`.
- App iPhone (SwiftUI, iOS 26 minimum) : projet décrit par XcodeGen
  (`ios/project.yml`, identifiant `io.github.shweepsi.heywatchlist`), Liquid
  Glass, même écran d'accueil.
- Textes en français uniquement, dans `strings.xml` et `Localizable.xcstrings`.
- Chaîne d'intégration continue GitHub Actions (voir `docs/CI.md`).
- Documentation : contexte du projet, plan par jalons, décisions, identité
  visuelle, reste à faire, règles pour les agents de code et guide de
  contribution.
