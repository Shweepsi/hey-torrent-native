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

- [ ] Le Worker relaie TMDB (clé de l'app) avec cache.
- [ ] Cœur : client TMDB (tendances, populaires, nouveautés, à venir, genres,
      recherche, fiche, casting, vidéos, saisons, plateformes en France,
      similaires) et cache SQLite hors ligne.
- [ ] Les trois onglets, carrousel, rangées, fiche détail plein écran,
      recherche instantanée, squelettes de chargement, sur iPhone et Android.
- [ ] Badge « Sur Plex » lu dans `library` (voir point à trancher n°2).
- [ ] Régler le Liquid Glass sur appareil (allers et retours TestFlight).

## Jalon 4 : watchlist et demandes

- [ ] Ajout en un geste, création de la demande, rattachement à une demande
      existante.
- [ ] États : demandée, en cours, disponible, refusée, déjà disponible.
- [ ] Synchronisation avec D1, file d'attente hors ligne, dernière
      modification gagnante.
- [ ] Séries : série entière ou saisons précises (à confirmer).

## Jalon 5 : espace admin (APK Android)

- [ ] Cœur, derrière `admin` : client Torznab (recherche en parallèle, délai de
      6 s par source), scoring des releases francophones (cas de test dans
      `core/tests/fixtures/`), client qBittorrent v4 et v5 avec catégories.
- [ ] Écrans admin dans `android/app/src/admin/` : file des demandes,
      résultats notés, envoi à qBittorrent, refus avec motif, suivi, réglages
      des serveurs (chiffrés sur Cloudflare), invitations.
- [ ] Vérifier les règles Google de vérification des développeurs pour les APK
      hors Play Store.

## Jalon 6 : notifications « Disponible sur Plex »

- [ ] **Firebase** : le compte de service `fcm-worker` et le secret
      `FCM_SERVICE_ACCOUNT` existent déjà ; reste l'envoi par l'API FCM HTTP v1.
      Supprimer les projets Google Cloud vides `heywatchlist` et
      `heywatchlist-push` créés lors des essais.
- [ ] Enregistrement des appareils (APNs et FCM).
- [ ] Webhooks Plex (`library.new`) et Tautulli, URL secrète, dédoublonnage.
- [ ] Mise à jour de `library`, passage des demandes en `disponible`, push vers
      toutes les personnes qui attendent.

## Jalon 7 : préparation des stores

- [ ] Politique de confidentialité, conditions, écran « À propos » avec
      l'attribution TMDB.
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
4. [ ] APK admin et vérification des développeurs Android.
5. [x] Backend en TypeScript.

## Risques à surveiller

- Pas de Mac : pas d'aperçus SwiftUI ni de simulateur en direct. Si les allers
  et retours TestFlight deviennent pénibles, louer un Mac dans le cloud
  quelques heures.
- Revue Apple : textes neutres et démo propre, même sans espace admin.
