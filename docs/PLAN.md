# Plan de développement : HeyWatchlist (`hey-torrent-native`)

> À lire avec `context_hey-torrent.md` (à la racine du dépôt). Ordre fixé par
> le propriétaire : **découverte d'abord, puis watchlist, puis espace admin.**
> Chaque jalon se termine par une version testable (TestFlight pour l'iPhone,
> APK pour Android).

---

## Jalon 0 : préparation (terminé)

- [x] Dépôt GitHub `hey-torrent-native` créé (privé) et ajouté au projet.
- [x] Compte **Apple Developer** (99 $/an) : TestFlight, push (APNs) et
      « Se connecter avec Apple ».
- [x] Compte **Cloudflare** (offre payante).
- [x] Clé d'API **TMDB**.
- [x] **Firebase** (FCM pour les notifications Android) : connecteur ajouté
      par Loïc le 2026-11-25. Les identifiants Google (« Se connecter avec
      Google ») et la clé FCM seront configurés aux jalons 2 et 6.
- [x] Plex (Docker sur Unraid) et qBittorrent répondent en HTTPS depuis
      l'extérieur.

> **Statut au 2026-11-26 : jalon 4 (watchlist) écrit**, en attente de la CI
> et d'un essai sur appareil ; les jalons 5 et 6 se préparent en parallèle.
>
> Historique : **au 2026-11-25, jalon 1 en cours.** Le squelette (cœur Rust,
> Android, iOS par XcodeGen, CI) est écrit sur une branche. Seul le cœur Rust
> se compile et se teste dans l'environnement de développement ; Android et iOS
> ne peuvent être compilés que par la CI GitHub Actions (pas de SDK Android ni
> de Xcode ici), donc les premiers tours de CI serviront à corriger les
> versions et réglages.

---

## Jalon 1 : fondations et chaîne de compilation (≈ 1 semaine)

Objectif : une app vide mais **installable sur ton iPhone et ton Android**,
avant d'écrire la moindre fonctionnalité. C'est le jalon qui lève le plus
gros risque (pas de Mac).

- Monorepo : `core/`, `android/`, `ios/`, `scripts/`, `docs/` (le dossier
  `backend/` arrive au jalon 2).
- `core/` : crate Rust avec `tokio`, `thiserror`, UniFFI en macros
  (`#[uniffi::export]`), fonctionnalité Cargo `admin`. Une fonction de test
  `ping()` appelée par les deux apps. `reqwest` + `rustls` ne sont ajoutés
  qu'avec le premier appel réseau (aucun au jalon 1).
- Android : projet Compose, variantes `public` et `admin`, compilation du
  cœur avec `cargo-ndk`, premier `HeyTheme` (couleurs, verre, typographie).
- iOS : projet Xcode **généré par XcodeGen** (un fichier `project.yml`
  lisible et versionné, puisqu'on ne peut pas ouvrir Xcode), XCFramework du
  cœur, cible iOS 26.
- CI GitHub Actions :
  - Linux : `cargo fmt`, `clippy`, `test` ;
  - Android : build et tests, APK admin en artefact ;
  - macOS : build iOS et tests, puis **envoi TestFlight avec fastlane** sur
    `main`.
- Fini quand : tu installes l'app iPhone via TestFlight et l'APK sur Android,
  et les deux affichent « pong » venu du Rust.

## Jalon 2 : backend Cloudflare et connexion (≈ 1 semaine)

- `backend/` : Worker en **TypeScript** (meilleurs outils pour D1 et les
  tests ; le Rust reste dans le cœur), déployé par `wrangler` depuis la CI.
- Schéma D1 : `users` (rôle), `identities`, `invitations`, `sessions`,
  `used_nonces`. Les autres tables (`watchlist_items`, `requests`,
  `request_followers`, `devices`, `credentials` chiffrés, `library`,
  `notified`) arrivent avec leur jalon, en nouvelles migrations.
- Connexion Apple et Google, vérification des jetons, JWT court et jeton de
  rafraîchissement, **invitations**, suppression de compte.
- Tests du Worker avec Vitest et l'environnement local de Wrangler.
- Fini quand : un invité se connecte avec Apple sur iPhone et voit son
  prénom dans Réglages.

## Jalon 3 : Découvrir (priorité n°1, ≈ 2 semaines)

- Le Worker relaie TMDB avec la clé de l'app, plus un **cache** (Cache API
  de Cloudflare) pour aller vite et ménager le quota.
- Cœur : client TMDB (tendances, populaires, nouveautés, à venir, genres,
  recherche, fiche complète, casting, vidéos, saisons, plateformes en
  France, similaires), cache SQLite local pour le hors ligne.
- Écrans iPhone et Android : Découvrir (carrousel, rangées), **fiche détail**
  plein écran, **recherche** instantanée, squelettes de chargement.
- Badge « Sur Plex » lu dans la table `library` de D1 (voir point à
  trancher n°2).
- Fini quand : on parcourt tout le catalogue en français, vite, sur les deux
  téléphones.

## Jalon 4 : Watchlist et demandes (≈ 1 à 2 semaines)

- Ajout en un geste depuis une affiche ou une fiche, création de la demande,
  rattachement si quelqu'un l'a déjà demandée.
- États : demandée, en cours, disponible, refusée, déjà disponible.
- Synchronisation avec D1, file d'attente hors ligne, règle « la dernière
  modification gagne ».
- Séries : série entière ou saisons précises.
- Fini quand : Camille demande un film sur iPhone, et la demande apparaît
  dans la base.

## Jalon 5 : espace admin, APK Android (≈ 2 semaines)

- Cœur, derrière `admin` : client **Torznab** (Prowlarr), recherche en
  parallèle avec délai de 6 s, **scoring** des releases francophones (repris
  des règles de l'ancien dépôt `hey-torrent`, avec ses cas de test), client
  **qBittorrent** v4 et v5 avec catégories.
- Écrans admin Android : file des demandes, résultats notés, envoi à
  qBittorrent, refus avec motif, suivi des téléchargements, réglages des
  serveurs (chiffrés sur Cloudflare), invitations.
- Fini quand : tu traites une demande de bout en bout depuis l'APK.

## Jalon 6 : notifications « Disponible sur Plex » (≈ 1 semaine)

- Enregistrement des appareils (APNs et FCM).
- Webhooks Plex (`library.new`) et Tautulli, URL secrète, dédoublonnage.
- Mise à jour de `library` et passage des demandes en `disponible`, push
  vers toutes les personnes qui attendent.
- Fini quand : le film arrive sur Plex et Camille reçoit la notification.

## Jalon 7 : préparation des stores (≈ 1 semaine)

- Politique de confidentialité, conditions, écran « À propos » avec la
  mention TMDB.
- Serveur de démo avec du contenu libre et un compte de démo.
- Bêta TestFlight avec tes proches, test interne Play Store.
- Fiches store, captures, questionnaires de confidentialité, envoi en revue.

**Total estimé : 10 à 12 semaines** de travail, plus le temps des
validations.

---

## Points retenus par défaut (à confirmer)

1. ~~Connexion Google sur iPhone et Apple sur Android~~ : **décidé** au
   jalon 2 (`docs/DECISIONS.md`) : Apple sur iPhone, Google sur Android et
   sur iPhone, même compte pour la même adresse e-mail vérifiée.
2. **Comment savoir ce qui est « Sur Plex » pour les invités ?** Ils n'ont
   pas accès à ton serveur. Proposition : la table `library` dans D1,
   remplie par les webhooks et par une **synchronisation complète chaque
   nuit** (un Cron Trigger du Worker interroge ton Plex en HTTPS).
3. **Dépôt public ou privé ?** En privé, GitHub Actions offre 2 000 minutes
   gratuites par mois, mais une minute macOS en compte 10 : il reste environ
   **200 minutes de build iPhone par mois**, soit 15 à 20 envois sur
   TestFlight. En public, c'est illimité et gratuit, mais tout le code est
   visible (sans aucun secret, bien sûr). Proposition : privé, avec le job
   iOS lancé seulement quand `core/` ou `ios/` changent, et un budget de
   quelques dollars si besoin.
4. **APK admin et vérification des développeurs Android** : Google
   impose progressivement que même les APK installés hors Play Store soient
   signés par un développeur vérifié. À vérifier au moment du jalon 5 ;
   l'inscription comme développeur Android suffit normalement.

## Risques connus

- **Pas de Mac** : pas d'aperçus SwiftUI ni de simulateur en direct ; chaque
  essai sur iPhone passe par la CI et TestFlight (quelques minutes). Si ça
  devient pénible, louer un Mac dans le cloud quelques heures.
- **Liquid Glass** se règle mieux en voyant l'écran : prévoir des allers et
  retours TestFlight sur le jalon 3.
- **Revue Apple** : même sans espace admin, garder les textes neutres et la
  démo propre (jalon 7).
