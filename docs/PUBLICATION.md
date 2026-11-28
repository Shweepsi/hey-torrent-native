# Publication : App Store non répertorié et test fermé Google Play

Décidé le 2026-11-27 (`docs/DECISIONS.md`) : HeyWatchlist reste **privée**,
réservée au cercle de l'administrateur.

- **iPhone** : App Store en **distribution non répertoriée** (unlisted). L'app
  passe la revue d'Apple comme une autre, puis n'apparaît ni dans la recherche
  ni dans les classements : seul le lien direct l'ouvre. TestFlight interne ne
  sert qu'à vérifier un build avant de l'envoyer en revue.
- **Android** : Google Play, **test fermé**, testeurs inscrits par adresse e-mail.
  L'app y reste : la règle « 12 testeurs pendant 14 jours » des nouveaux
  comptes personnels ne conditionne que l'accès à la **production**, dont on
  n'a pas besoin.
- **APK admin** : jamais sur Google Play. Il reste un artefact de la CI,
  installé à la main sur le téléphone de l'administrateur.

Sources lues le 2026-11-27 :
[Unlisted app distribution](https://developer.apple.com/support/unlisted-app-distribution),
[Exigences de test des nouveaux comptes personnels](https://support.google.com/googleplay/android-developer/answer/14151465),
[Tests ouverts, fermés ou internes](https://support.google.com/googleplay/android-developer/answer/9845334),
[fastlane supply](https://docs.fastlane.tools/actions/supply/) (premier envoi
manuel, compte de service).

## Ce qui est automatique

| Quoi | Quand | Où |
|---|---|---|
| Build iPhone signé envoyé sur TestFlight | chaque fusion dans `main` qui touche l'app, ou **Run workflow** | `ci-ios.yml` (déjà en place, premier envoi réussi le 2026-11-26) |
| AAB public signé envoyé sur Google Play, piste « Test fermé » | chaque tag `v*`, ou **Run workflow** avec « Envoyer aussi l'AAB sur Google Play » | `ci-android.yml`, script `.github/scripts/play-upload.mjs` |
| Notes de version Google Play (fr-FR) | à chaque envoi | première entrée de `CHANGELOG.md`, 500 caractères au plus |
| Politique de confidentialité et page de suppression du compte | toujours | Worker : `/confidentialite`, `/suppression-du-compte` |
| Lien « Politique de confidentialité » et mention TMDB | toujours | Réglages, iPhone et Android |

Chaque envoi sur Google Play demande un **numéro de version nouveau** : il vient
de `Cargo.toml` (0.7.0 donne 700). Une version = un tag. Si l'app n'a encore
jamais été publiée, Google n'accepte qu'un brouillon : le script l'envoie en
brouillon et le dit dans le journal.

## Ce que l'administrateur fait, dans l'ordre

Les secrets passent **toujours par hey-kit** (onglet « Mes apps »), jamais par
le chat ni par un fichier du dépôt.

### 1. Serveur

1. hey-kit > Mes apps > HeyWatchlist : envoyer `PRIVACY_CONTACT` (l'adresse
   e-mail de contact affichée sur la politique de confidentialité).
2. Ouvrir `https://heywatchlist-api.<sous-domaine>.workers.dev/confidentialite`
   et `/suppression-du-compte` : relire. Ce sont les deux URL à donner aux
   stores. La page promet une suppression sous 30 jours sur demande écrite :
   c'est un engagement de l'administrateur.

### 2. iPhone : vérifier sur TestFlight

1. hey-kit > Mes apps : créer et envoyer la clé **Sign in with Apple** et la
   clé **APNs** (guides du kit). Sans elles, la connexion Apple et les
   notifications ne marchent pas, et Apple refusera l'app.
2. App Store Connect > HeyWatchlist > **TestFlight** > Test interne > **+** :
   groupe « Moi », y ajouter son compte et le dernier build. Installer l'app
   **TestFlight** sur l'iPhone, puis HeyWatchlist.
3. Essayer : connexion Apple et connexion Google (un nouveau compte attend
   ta validation dans l'onglet Accès de l'APK admin),
   Découvrir, ajout à la watchlist,
   suppression d'un compte de test.

### 3. iPhone : fiche App Store

App Store Connect > HeyWatchlist > **Distribution** :

- Textes : voir « Textes des fiches » plus bas. Catégorie : Divertissement.
- URL de la politique de confidentialité : `…/confidentialite`.
- **Confidentialité de l'app** : voir « Réponses de confidentialité ».
- Classification par âge : répondre au questionnaire (affiches et
  bandes-annonces de films, pas de contenu généré partagé entre utilisateurs,
  pas de discussion, pas d'achat).
- Prix : gratuit. Disponibilité : France (et les pays du cercle).
- **Captures** : 3 à 10 captures iPhone au format 6,9 pouces (1320 × 2868) ou
  6,5 pouces (1284 × 2778). Un iPhone « Pro Max » ou « Plus » les fait
  directement ; avec un autre modèle, il faudra les produire autrement (à
  préparer avec Claude).
- Informations pour la revue : voir « Notes de revue ».
- Build : celui vérifié sur TestFlight.

### 4. iPhone : revue puis distribution non répertoriée

1. **Ajouter à la revue** puis **Envoyer**. Choisir une publication
   **manuelle** (pas automatique), pour que l'app ne sorte pas publique.
2. Juste après l'envoi : remplir la demande de distribution non répertoriée,
   <https://developer.apple.com/contact/request/unlisted-app/>. Apple la refuse
   si l'app n'a pas été envoyée en revue ou si elle est présentée comme une
   bêta.
3. Une fois les deux acceptés : la méthode de distribution devient « App non
   répertoriée » (Tarifs et disponibilité), publier la version, partager le
   lien App Store reçu. Le changement vaut pour toutes les versions suivantes.
4. Mises à jour : la CI envoie chaque build sur TestFlight ; le vérifier, puis
   créer la nouvelle version dans App Store Connect et l'envoyer en revue.

### 5. Android : compte et app Google Play

1. Créer un compte **personnel** de développeur Google Play
   (<https://play.google.com/console/signup>) : frais d'inscription uniques,
   vérification de l'identité (nom et adresse légale du profil de paiement),
   puis vérification d'un téléphone Android avec l'app **Play Console**.
2. **Créer une application** : nom `HeyWatchlist`, langue par défaut français
   (France), application, gratuite.
3. **Contenu de l'application** (menu « Règles et programmes ») :
   - Règles de confidentialité : `…/confidentialite`.
   - Accès à l'application : « Certaines fonctionnalités sont soumises à
     restrictions », avec le compte Google de démonstration et les
     instructions (voir « Notes de revue »).
   - Annonces : aucune. Public cible : 18 ans et plus.
   - Classification du contenu : questionnaire, catégorie « Divertissement »
     ou « Référence » ; pas d'échange entre utilisateurs, pas d'achat.
   - Sécurité des données : voir « Réponses de confidentialité ».
     URL de suppression du compte : `…/suppression-du-compte`.
   - Applications gouvernementales, fonctionnalités financières, santé,
     actualités : non.
4. **Fiche principale du Play Store** : textes plus bas, icône 512 × 512,
   image de présentation 1024 × 500, au moins 2 captures de téléphone (celles
   du téléphone de l'administrateur conviennent).

### 6. Android : premier envoi sur le test fermé

1. Tester et publier > Tests > **Test fermé** > piste par défaut > Testeurs :
   créer une liste avec les adresses Google du cercle. Pays : France.
2. **Premier AAB à la main** (Google l'exige avant tout envoi par l'API) :
   GitHub > Actions > CI Android > dernière exécution de `main` >
   artefact `heywatchlist-public-release-aab`, le dézipper, le déposer dans
   « Créer une version ». Accepter la **signature d'application par Google
   Play** : le keystore de la CI devient la clé d'importation.
3. Envoyer en examen. Une fois l'app publiée sur la piste, copier le **lien
   d'activation** (onglet Testeurs) et l'envoyer au cercle.
4. **Connexion Google sur la version Play** : Google re-signe l'app avec sa
   propre clé. Copier son empreinte SHA-1 (Tester et publier > Configuration >
   **Intégrité de l'application** > Signature de l'application), puis la
   déclarer : console Google Cloud, client OAuth **Android** pour
   `io.github.shweepsi.heywatchlist` avec cette empreinte, et Firebase
   (paramètres de l'app Android, « Ajouter une empreinte »). L'empreinte est
   publique : Claude peut faire la partie Firebase.
5. Les personnes qui ont installé l'APK public à la main le **désinstallent**
   avant d'installer la version Play (signatures différentes).

### 7. Android : envois automatiques

1. Google Cloud, projet `heywatchlist-60448` : activer l'**API Google Play
   Android Developer**, créer un compte de service (sans rôle), lui créer une
   clé **JSON**. hey-kit > Mes apps : déposer le fichier
   (`PLAY_SERVICE_ACCOUNT_JSON`), le kit l'envoie en secret GitHub ; supprimer
   ensuite le fichier téléchargé.
2. Play Console > **Utilisateurs et autorisations** > Inviter : l'adresse du
   compte de service, accès à l'app HeyWatchlist, autorisations « Publier sur
   les canaux de test » et « Gérer les canaux de test et modifier les listes de
   testeurs ».
3. Ensuite, chaque tag `v*` (ou **Run workflow** avec la case Google Play)
   envoie la version sur le test fermé ; Google l'examine avant de la proposer.

## Notes de revue

Depuis la v0.8.0 (PR #16), il n'y a plus de code d'invitation : tout nouveau
compte attend la validation de l'administrateur (onglet **Accès** de l'APK
admin), sauf une adresse **acceptée d'avance**. La revue passe par un compte de
démonstration accepté d'avance :

1. Créer un compte Google dédié (par exemple « HeyWatchlist Démo »), avec un
   mot de passe fort rangé dans hey-kit (Coffre, dossier HeyWatchlist), sans
   double authentification ni téléphone de secours : sinon le relecteur reste
   bloqué sur une vérification qu'il ne peut pas faire.
2. APK admin > Accès > adresses acceptées d'avance : ajouter cette adresse.
3. Se connecter une fois avec ce compte sur l'iPhone (TestFlight) pour vérifier
   l'accès direct, puis ajouter un ou deux titres à sa watchlist pour que le
   relecteur voie des demandes.
4. Renseigner l'identifiant et le mot de passe dans le formulaire du store :
   Apple, Informations pour la revue > **Connexion requise** ; Google, Accès à
   l'application. Ces deux champs sont privés, réservés aux relecteurs ; le
   mot de passe ne va nulle part ailleurs (ni chat ni dépôt).

Pendant une revue, garder l'œil sur l'onglet Accès : un relecteur qui se
connecte avec son propre compte apparaît en attente, à accepter.

Texte pour Apple (Informations pour la revue > Notes) et Google (Accès à
l'application > Instructions) :

> HeyWatchlist est une app privée pour les proches d'un particulier qui partage
> son serveur Plex personnel. Elle est destinée à la distribution non
> répertoriée : seules les personnes que l'administrateur accepte l'utilisent.
>
> Pour tester : touchez « Se connecter avec Google » et utilisez le compte de
> démonstration fourni, déjà accepté par l'administrateur. Un autre compte
> Apple ou Google est créé en attente de validation, ce qui est le
> fonctionnement normal de l'app. Vous pouvez parcourir les films et séries
> (données TMDB), ajouter un titre à votre watchlist, ce qui envoie une demande
> à l'administrateur, suivre l'état de vos demandes et supprimer le compte
> (Réglages > Supprimer mon compte ; pour le compte de démonstration, merci
> de ne pas le supprimer).
>
> L'app ne fournit, n'héberge et ne recommande aucun contenu à télécharger. Les
> demandes sont traitées par l'administrateur, hors de l'app. Les
> notifications ne signalent qu'un titre demandé devenu disponible sur son
> serveur, ou la validation de votre accès.

## Textes des fiches

À relire avant de les coller. Aucune marque tierce dans le nom ni le
sous-titre ; « compatible avec Plex » est permis dans la description.

| Champ | Limite | Texte |
|---|---|---|
| Nom (les deux stores) | 30 | HeyWatchlist |
| Sous-titre (Apple) | 30 | Demandez vos films et séries |
| Description courte (Google) | 80 | Découvrez des films et séries et demandez-les au serveur de vos proches. |
| Mots-clés (Apple) | 100 | watchlist,films,séries,demandes,serveur,cinéma,bandes-annonces,notification |
| Texte promotionnel (Apple) | 170 | Parcourez les nouveautés, ajoutez un titre à votre watchlist et soyez prévenu dès qu'il est disponible sur le serveur de vos proches. |

Description (les deux stores) :

> HeyWatchlist relie les proches d'un passionné de cinéma à son serveur
> multimédia personnel, compatible avec Plex. L'app est réservée aux personnes
> que l'administrateur du serveur accepte.
>
> • Découvrir : tendances, nouveautés, à venir, genres, recherche instantanée,
> fiches complètes avec casting, saisons et bandes-annonces.
> • Watchlist : ajoutez un film, une série entière ou certaines saisons. Chaque
> ajout devient une demande adressée à l'administrateur.
> • Suivi : demandée, en cours, disponible ou refusée, vous voyez où en est
> chaque demande.
> • Notifications : un message quand un titre demandé arrive sur le serveur.
>
> HeyWatchlist ne fournit ni n'héberge aucun contenu. Les informations sur les
> films et séries viennent de TMDB ; cette app utilise l'API de TMDB sans être
> approuvée ni certifiée par TMDB.

## Réponses de confidentialité

Elles suivent `backend/src/legal.ts` ; à revoir si l'app collecte autre chose.

### Apple, « Confidentialité de l'app » (app iPhone)

Suivi (tracking) : **non**. Données collectées, toutes **liées à l'identité**,
pour le seul **fonctionnement de l'app** :

| Catégorie Apple | Donnée | Pourquoi |
|---|---|---|
| Coordonnées > Adresse e-mail | e-mail vérifié par Apple ou Google | relier deux connexions d'une même personne |
| Coordonnées > Nom | prénom | affiché dans l'app et à l'administrateur |
| Identifiants > Identifiant utilisateur | identifiant Apple ou Google, identifiant du compte | compte |
| Contenu utilisateur > Autre contenu | watchlist et demandes | fonction principale |

L'app iPhone n'embarque pas Crashlytics : rien à déclarer en « Diagnostics ».
Le jeton de notification sert au seul envoi des notifications.

### Google, « Sécurité des données » (app publique)

Données chiffrées en transit : **oui**. Suppression sur demande : **oui**
(dans l'app et par `…/suppression-du-compte`). Aucune donnée **partagée** (les
prestataires techniques ne comptent pas comme un partage). Données
**collectées**, toutes obligatoires :

| Catégorie Google | Donnée | Finalités |
|---|---|---|
| Informations personnelles > Nom | prénom | fonctionnement de l'app, gestion du compte |
| Informations personnelles > Adresse e-mail | e-mail vérifié | gestion du compte |
| Informations personnelles > ID utilisateur | identifiant Google ou Apple | gestion du compte |
| Activité dans l'application > Autre contenu généré par l'utilisateur | watchlist et demandes | fonctionnement de l'app |
| Appareil ou autres ID | jeton de notification | fonctionnement de l'app |
| Infos et performances de l'application > Journaux de plantage, Diagnostics | Firebase Crashlytics | analyse (corriger les plantages) |
