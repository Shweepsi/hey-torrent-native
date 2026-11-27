# Reste à faire

Travail restant après l'écriture du squelette du jalon 1. Le détail de chaque
jalon est dans `docs/PLAN.md` ; les décisions dans `docs/DECISIONS.md`. Cocher
une ligne ici seulement quand elle est faite **et vérifiée**.

## Pour clore le jalon 1

Le jalon 1 est fini quand le propriétaire installe l'app iPhone par TestFlight
et l'APK sur Android, et que les deux affichent « pong » venu du Rust. Rien de
cela n'a encore été compilé : seul le cœur Rust se teste en local, Android et
iOS ne se compilent que sur GitHub Actions.

- [ ] Premiers tours de CI : corriger les versions et réglages (Gradle, AGP,
      Compose, XcodeGen, Xcode, fastlane) qui n'ont pas pu être vérifiés sans
      SDK Android ni Mac.
- [ ] Créer l'identifiant d'app `io.github.shweepsi.heywatchlist` chez Apple
      (`docs/CI.md`, étape 2), avant le premier envoi : il est définitif après
      publication. Le code l'utilise déjà (`applicationId` Android, bundle iOS).
- [ ] Secrets GitHub : signature Android, certificats et clé d'API App Store
      Connect pour fastlane, `GOOGLE_SERVICES_JSON` (voir `docs/CI.md`).
- [ ] Signature iOS configurée (workflow manuel « iOS - configurer la
      signature », voir `docs/CI.md`), premier envoi TestFlight réussi, puis
      installation sur iPhone.
- [ ] APK admin récupéré en artefact de la CI, installé sur Android.
- [ ] iOS : définir le thème clair (l'app est forcée en sombre au jalon 1).
- [ ] Accessibilité du verre : « Réduire la transparence » (iOS) et
      « Supprimer les animations » (Android), voir `docs/DESIGN.md`.

## Jalon 2 : backend Cloudflare et connexion

Le code est écrit (`backend/`, `core/src/account.rs`, écrans d'accueil et de
Réglages) ; le Worker et le cœur sont testés en local. Reste à le vérifier en
vrai :

- [ ] Lancer `node backend/scripts/configurer-backend.mjs` sur le PC du
      propriétaire (jeton Cloudflare, secrets du Worker, réglages GitHub).
- [ ] Premier déploiement du Worker et migrations D1 par la CI (fusion dans
      `main`), puis `GET /v1/health` qui répond.
- [ ] Premiers tours de CI Android et iOS du jalon : Credential Manager,
      `WebAuthenticationSession`, Keychain, Keystore n'ont jamais été compilés.
- [ ] Capacité « Sign in with Apple » ajoutée par la CI à l'identifiant d'app,
      profil régénéré.
- [ ] Fini quand : le propriétaire se connecte avec Apple sur iPhone (code
      administrateur), crée une invitation depuis l'APK admin, et un invité se
      connecte avec Apple et voit son prénom dans Réglages.
- [ ] Clients Google (Web, Android ×2 avec SHA-1, iOS) et écran de
      consentement en production, puis relancer le script pour les poser.
- [ ] Clé « Sign in with Apple » pour la révocation à la suppression du compte
      (exigée par Apple avant la publication).

## Jalon 3 : Découvrir (priorité n°1)

Écrit (v0.3.0). La CI de la PR #9 est verte : les apps Android et iPhone se
compilent et leurs tests passent (le 2026-11-26). Rien n'a encore été vu sur
appareil.

- [x] Le Worker relaie TMDB (clé de l'app) avec cache D1 ; 130 tests Vitest
      passent en local.
- [x] Cœur : client TMDB et cache SQLite hors ligne ; tests `wiremock` passent
      en local.
- [ ] Poser le secret `TMDB_API_KEY` sur le Worker (relancer
      `backend/scripts/configurer-backend.mjs`), puis déployer : sans lui le
      relais répond `provider_unavailable`.
- [ ] Voir les écrans Découvrir, Genre, recherche et fiche sur un vrai
      appareil (APK, TestFlight) : la CI prouve la compilation et les tests,
      pas le rendu.
- [ ] Confirmer la raison `E174.1` de `DiskSpace` dans `PrivacyInfo.xcprivacy`.
- [ ] Badge « Sur Plex » lu dans `library` (voir point à trancher n°2) : pas
      codé tant que le propriétaire n'a pas choisi.
- [ ] Régler le Liquid Glass et le contraste des affiches claires sur appareil
      (allers et retours TestFlight) ; vérifier R8 avec Coil (`assemblePublicRelease`).
- [ ] Vérifier sur iOS 26 : `.searchable` dans `Tab(role: .search)`, transition
      zoom de la fiche, pluriels des chaînes.
- [ ] Vider la pile de navigation de Découvrir à l'expiration de session ?
      (choix produit, non tranché ; comme la déconnexion volontaire, on la garde).

## Jalon 4 : watchlist et demandes

- [x] Ajout depuis la fiche, création de la demande, rattachement à une
      demande existante (Worker, cœur, Android, iPhone).
- [x] États : demandée, en cours, disponible, refusée.
- [x] Synchronisation avec D1, file d'attente hors ligne, dernière
      modification gagnante.
- [x] Séries : série entière ou saisons au choix (décidé le 2026-11-26).
- [ ] CI verte sur Android et iPhone, puis essai sur appareil : Camille
      demande un film sur iPhone et la demande apparaît dans D1.
- [ ] État « déjà disponible » : la table `library` existe (jalon 6), remplie
      par les webhooks ; reste à la lire (point à trancher n°2).
- [ ] Ajout en un geste depuis une affiche (appui long dans Découvrir).

## Jalon 5 : espace admin (APK Android)

- [x] Cœur, derrière `admin` : client Torznab (recherche en parallèle, délai de
      6 s par source, résultats au fil de l'eau), tri des releases
      francophones (cas de test dans `core/tests/fixtures/`), client
      qBittorrent v4 et v5 avec catégories et étiquettes.
- [x] Worker : routes `/v1/admin/…` (demandes, réglages chiffrés,
      invitations), migration `0005_admin.sql`, secret `CREDENTIALS_KEY`
      posé par `configurer-backend.mjs`.
- [x] Écrans admin dans `android/app/src/admin/` : demandes, fiche avec
      recherche et envoi à qBittorrent, refus avec motif, réouverture, suivi
      des téléchargements, réglages des serveurs, invitations.
- [ ] Relancer `configurer-backend.mjs` pour poser `CREDENTIALS_KEY`, puis
      déployer le Worker (migration 0005).
- [ ] CI verte, puis essai de bout en bout sur l'APK admin : saisir Prowlarr
      et qBittorrent dans Serveurs, traiter une demande jusqu'à qBittorrent.
- [ ] Ajuster les poids du tri après les premiers vrais essais
      (`core/src/admin/scoring.rs`).
- [x] Vérification des développeurs Android (point n°4) : voir
      `docs/DECISIONS.md`, « Décidé au jalon 5 ».

## Jalon 6 : notifications « Disponible sur Plex »

Écrit (v0.6.0). Worker et cœur testés en local (réponses simulées) ; Android et
iPhone ne se compilent que sur la CI. Rien n'a encore été reçu sur un téléphone.

- [x] Envoi par APNs (jeton ES256) et FCM HTTP v1 (compte de service), textes
      traduits par les apps (`loc-key`, `body_loc_key`).
- [x] Enregistrement des appareils (`PUT /v1/devices`), rattachés à leur
      session ; jetons périmés oubliés.
- [x] Webhooks Plex (`library.new`) et Tautulli, URL secrète régénérable
      (carte « Notifications Plex » de l'APK admin), dédoublonnage.
- [x] Mise à jour de `library`, passage des demandes en `disponible`, push vers
      les personnes qui attendent (filtrées par saisons voulues).
- [ ] Secret GitHub `GOOGLE_SERVICES_JSON` (base64 de `google-services.json`) :
      sans lui, les APK se compilent sans notifications.
- [ ] Clé APNs sur le Worker : relancer `configurer-backend.mjs` (étape 7/7).
- [ ] Capacité « Notifications push » ajoutée par la CI à l'identifiant d'app,
      profil régénéré (premier envoi TestFlight).
- [ ] Coller les URL dans Plex (Réglages, Webhooks) et dans Tautulli (agent
      Webhook, « Recently Added », corps JSON fourni par la carte).
- [ ] Bouton « Envoyer une notification d'essai » de Réglages sur iPhone et
      Android, puis fini quand : le film arrive sur Plex et Camille reçoit la
      notification.
- [ ] Ouvrir la fiche du titre à l'appui sur la notification (les données
      `media_type` et `tmdb_id` voyagent déjà avec elle).
- [ ] Supprimer les projets Google Cloud vides `heywatchlist` et
      `heywatchlist-push` créés lors des essais.

## Jalon 7 : préparation des stores

- [ ] Politique de confidentialité, conditions, écran « À propos » avec
      l'attribution TMDB.
- [ ] Déclarer Firebase Crashlytics (diagnostics de plantage, Android) dans la
      politique de confidentialité et « Data safety ».
- [ ] Serveur de démonstration (contenu libre uniquement) et compte de démo.
- [ ] Bêta TestFlight avec les proches, test interne Play Store.
- [ ] Fiches store, captures, questionnaires de confidentialité (« App
      Privacy », « Data safety »), envoi en revue.
- [ ] Réserver le nom HeyWatchlist sur App Store Connect et la Play Console.

## Points à trancher (par défaut, à confirmer)

1. [x] Connexion Google sur iPhone et Apple sur Android (jalon 2 : Apple sur
       iPhone, Google partout).
2. [ ] Comment savoir ce qui est « Sur Plex » pour les invités (table
       `library` et synchronisation nocturne proposées).
3. [ ] Dépôt privé ou public (minutes macOS de GitHub Actions).
4. [x] APK admin et vérification des développeurs Android (jalon 5 : rien à
       faire pour un APK installé par ADB ; compte gratuit si besoin).
5. [x] Backend en TypeScript.

## Risques à surveiller

- Pas de Mac : pas d'aperçus SwiftUI ni de simulateur en direct. Si les allers
  et retours TestFlight deviennent pénibles, louer un Mac dans le cloud
  quelques heures.
- Revue Apple : textes neutres et démo propre, même sans espace admin.
