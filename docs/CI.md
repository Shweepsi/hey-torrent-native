# Intégration continue (GitHub Actions)

Tout se compile et se teste sur GitHub : le propriétaire n'a pas de Mac, et
Android ne se compile pas non plus en local. Ce document dit **quoi créer, dans
quel ordre, et où lire les résultats**.

> État : les workflows passent `actionlint` 1.7.12 et les scripts `shellcheck`
> 0.11 ; les commandes du cœur Rust (`fmt`, `clippy` et `test`, sans puis avec
> `admin`) et le contrôle des liaisons (`check-bindings.sh`) ont été exécutés en
> local sur Linux ; `cargo-ndk` 4.1.2 a été compilé ; les tests de `buildSrc`
> ont tourné ; le `Fastfile` a été chargé par fastlane 2.242 et la lane `beta`
> déroulée avec de fausses clés jusqu'à `match` (aucun secret dans son journal).
> Le backend (`backend/`) : `npm run check` et ses 35 tests Vitest ont tourné en
> local dans `workerd` ; `configurer-backend.mjs` a été déroulé contre une API
> Cloudflare et un `gh` simulés, jamais contre les vrais services.
> **Rien n'a encore tourné sur GitHub, sur macOS ni chez Apple, et aucune
> compilation Android ou Xcode n'a eu lieu** (ni SDK Android ni Xcode dans
> l'environnement de développement) : les premiers tours de CI serviront à
> corriger des versions et des réglages.

## Les workflows

| Fichier | Se lance | Machine | Sert à |
|---|---|---|---|
| `ci-core.yml` | PR et `main`, si le cœur, les outils, les scripts ou Cargo changent | Linux | `cargo fmt`, `clippy -D warnings`, tests (sans puis avec `admin`) ; génération des liaisons Kotlin et Swift et contrôle du contrat (`admin_ping` seulement dans l'édition admin) ; ShellCheck |
| `ci-android.yml` | PR, `main`, tags `v*`, si Android, le cœur, `build-android-core.sh`, `generate-bindings.sh` ou Cargo changent | Linux | tests du script d'envoi Google Play, tests de `buildSrc`, `./gradlew testPublicDebugUnitTest testAdminDebugUnitTest assembleAdminDebug`, `assemblePublicRelease` (contrôle de R8, rien n'est publié) ; sur `main` ou un tag, **si les secrets Android existent** : APK admin signé (App Distribution, ou artefact sans sa clé), APK public signé et AAB public ; sur un tag `v*` ou à la demande, **si la clé Google Play existe** : AAB envoyé sur le test fermé |
| `ci-ios.yml` | PR (hors brouillons), `main`, tags `v*`, si iOS, le cœur, `build-ios-xcframework.sh`, `generate-bindings.sh` ou Cargo changent (jamais pour un `.md`) | **macOS** | XCFramework du cœur, XcodeGen, tests sur simulateur iPhone ; sur `main`, un tag ou un lancement manuel, **si les secrets iOS existent** : signature et envoi TestFlight |
| `ios-setup-signing.yml` | À la main seulement | **macOS** | Crée le certificat et le profil de distribution (fastlane match) |
| `ci-backend.yml` | PR et `main`, si `backend/` change | Linux | TypeScript et tests du Worker (Vitest dans `workerd`, rien n'appelle Cloudflare) ; sur `main`, **si les réglages Cloudflare existent** : migrations D1 puis déploiement du Worker `heywatchlist-api` |

Sans les secrets, rien n'échoue : l'étape d'envoi est ignorée et le journal
liste les noms manquants. Une PR ne reçoit jamais d'envoi TestFlight ni de
signature. Une PR en brouillon ne lance pas `CI iOS` ; la passer en « Ready for
review » le lance.

Les filtres de chemins évitent de payer du macOS pour rien : modifier un script
Android, une action Android ou un fichier `.md` ne lance pas `CI iOS`. Les tags
`v*` sont prévus pour lancer les workflows Android et iOS, mais on n'a pas pu
vérifier que GitHub ignore bien les filtres de chemins pour un tag : si un tag ne
déclenche rien, utilisez **Run workflow** en choisissant ce tag.

## Secrets et variables à créer

Dépôt GitHub > **Settings** > **Secrets and variables** > **Actions**. Les
**secrets** vont dans l'onglet « Secrets », les **variables** dans l'onglet
« Variables ». Les noms sont exacts, en majuscules.

### iPhone

| Nom | Type | Contenu |
|---|---|---|
| `ASC_KEY_ID` | secret | Identifiant de la clé d'API App Store Connect (10 caractères) |
| `ASC_ISSUER_ID` | secret | « Issuer ID » de l'équipe (un UUID) |
| `ASC_KEY_P8` | secret | Contenu **complet** du fichier `.p8`, lignes `BEGIN` et `END` comprises (le base64 de ce fichier est aussi accepté) |
| `MATCH_PASSWORD` | secret | Phrase secrète de votre choix, qui chiffre les certificats dans le dépôt privé |
| `MATCH_GIT_TOKEN` | secret | Jeton GitHub avec accès en écriture au dépôt privé des certificats |
| `APPLE_TEAM_ID` | variable | Identifiant d'équipe Apple (10 caractères) |
| `MATCH_GIT_URL` | variable | URL HTTPS du dépôt privé des certificats |

### Android

| Nom | Type | Contenu |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | secret | Le keystore `.jks`, encodé en base64 |
| `ANDROID_KEYSTORE_PASSWORD` | secret | Mot de passe du keystore |
| `ANDROID_KEY_ALIAS` | secret | Alias de la clé (`heywatchlist` ci-dessous) |
| `ANDROID_KEY_PASSWORD` | secret | Mot de passe de la clé (le même que celui du keystore) |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | Clé JSON du compte de service Google Play (brute ou en base64), posée par hey-kit : envoi de l'AAB public sur le test fermé (`docs/PUBLICATION.md`, étape 7) |
| `PLAY_TRACK` | variable, facultative | Piste Google Play : `alpha` (test fermé, par défaut), `internal`, `beta` |
| `FIREBASE_DISTRIBUTION_KEY` | secret | Clé JSON (brute ou en base64) d'un compte de service au rôle « Firebase App Distribution Admin » : envoi privé de l'APK admin (voir « APK admin privé » ci-dessous), posée par hey-kit |
| `FIREBASE_TESTERS` | secret | E-mail(s) qui reçoivent l'APK admin, séparés par des virgules, posé par hey-kit. Secret pour rester masqué dans les journaux, publics ici |

### APK admin privé

Le dépôt est public : n'importe quel compte GitHub peut télécharger les
artefacts d'une exécution. L'APK admin n'y est donc plus publié ; il part par
[Firebase App Distribution](https://firebase.google.com/docs/app-distribution/android/distribute-cli)
vers les seules adresses de `FIREBASE_TESTERS`, qui le reçoivent par e-mail et
l'installent depuis leur téléphone. Tout se règle dans hey-kit, onglet « Mes
apps », guide « APK admin privé » : démarrer App Distribution pour l'app admin
dans la console Firebase, créer le compte de service et sa clé, saisir son
e-mail, puis « Envoyer ». Pas encore essayé en vrai (2026-11-28).

Tant que ces secrets manquent, l'APK admin signé reste en artefact (7 jours) comme
avant. L'APK admin de debug n'est plus jamais publié.

Notifications (jalon 6) : `GOOGLE_SERVICES_JSON`, le contenu de
`google-services.json` encodé en base64 (le JSON brut est aussi accepté). Lu par
`android/app/build.gradle.kts` ; absent, les APK se compilent sans
notifications. La CI iOS ajoute aussi la capacité « Notifications push » à
l'identifiant d'app.

### Serveur (jalon 2)

Tous créés par `node backend/scripts/configurer-backend.mjs`, qui pose aussi
les secrets du Worker (`backend/README.md`).

| Nom | Type | Contenu |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` | secret | Jeton d'API Cloudflare : Workers Scripts (Edit), D1 (Edit), Account Settings (Read) |
| `CLOUDFLARE_ACCOUNT_ID` | variable | Identifiant du compte Cloudflare |
| `HEYWATCHLIST_API_URL` | variable | Adresse du Worker, `https://heywatchlist-api.<sous-domaine>.workers.dev`, inscrite dans les apps Android et iPhone à la compilation |

Sans `HEYWATCHLIST_API_URL`, les apps se compilent quand même, mais la connexion
répond « Cette version de l'app ne connaît pas l'adresse du serveur ». La CI iOS ajoute toute seule la capacité « Sign in
with Apple » à l'identifiant d'app chez Apple et régénère le profil si besoin.

## Première installation, dans l'ordre

En résumé : fusionnez la PR du jalon 1 (les workflows lancés à la main
n'existent qu'une fois sur `main`), faites les étapes 1 à 4 (identifiant, Apple,
certificats, secrets), lancez la configuration de la signature (étape 5), puis le
premier envoi TestFlight (étape 6). Android a besoin de l'étape 7 seulement pour un APK signé ;
l'APK de debug de chaque exécution marche sans rien configurer.

### 1. Fixer l'identifiant de l'app (avant tout)

L'identifiant de bundle iOS est lu **uniquement** dans `ios/project.yml`
(`PRODUCT_BUNDLE_IDENTIFIER` de la cible `HeyWatchlist`) ; fastlane n'en
connaît pas d'autre. Il est **définitif après la première publication**.

Le propriétaire a décidé `io.github.shweepsi.heywatchlist` pour l'app publique
(Android et iOS) et `io.github.shweepsi.heywatchlist.admin` pour l'édition admin
(`docs/DECISIONS.md`, `context_hey-torrent.md` §1.3). `ios/project.yml` et
l'`applicationId` Android (`android/app/build.gradle.kts`) les portent déjà : ce
sont les noms des deux apps Android déclarées dans Firebase, et le plugin
Firebase des jalons 2 et 6 refusera un `google-services.json` dont les noms de
paquet diffèrent de l'`applicationId`. Le `namespace` Kotlin (`app.heywatchlist`)
est un autre réglage, sans lien avec l'identifiant publié. Elle ne
doit plus changer une fois l'app créée chez Apple. La suite utilise
`<identifiant>` pour `io.github.shweepsi.heywatchlist`. La CI n'a rien à
changer : fastlane relit l'identifiant dans `ios/project.yml`.

### 2. Chez Apple (compte Apple Developer)

1. **Équipe** : <https://developer.apple.com/account> > **Membership details** >
   copier le **Team ID** dans la variable `APPLE_TEAM_ID`.
2. **Identifiant d'app** : **Certificates, Identifiers & Profiles** >
   **Identifiers** > **+** > **App IDs** > **App** > description `HeyWatchlist`,
   **Bundle ID** en mode *Explicit* = `<identifiant>`. Aucune capacité à
   cocher : la CI ajoute elle-même « Se connecter avec Apple » et
   « Notifications push » (fastlane, `ensure_capabilities`) et régénère le
   profil.
3. **Fiche de l'app** : <https://appstoreconnect.apple.com> > **Apps** > **+** >
   **Nouvelle app** : plateforme iOS, nom `HeyWatchlist`, langue principale
   français, identifiant = `<identifiant>`, UGS `heywatchlist`, accès complet.
   Sans cette fiche, l'envoi TestFlight échoue.
4. **Clé d'API** : App Store Connect > **Utilisateurs et accès** >
   **Intégrations** > **App Store Connect API** > **Clés d'équipe** > **+**.
   Nom `GitHub Actions`, accès **Admin** (le titulaire du compte doit d'abord
   demander l'accès à l'API). Noter le **Issuer ID** (au-dessus du tableau) et
   l'**ID de la clé** ; **télécharger le `.p8` tout de suite** : Apple ne le
   propose qu'une fois. Ouvrez-le dans un éditeur de texte et copiez tout son
   contenu dans le secret `ASC_KEY_P8`.

### 3. Le dépôt privé des certificats (match)

1. GitHub > **New repository** > nom `heywatchlist-certificates`, **Private**,
   sans README. Variable `MATCH_GIT_URL` =
   `https://github.com/<compte>/heywatchlist-certificates.git`.
2. GitHub > **Settings** (votre profil) > **Developer settings** > **Personal
   access tokens** > **Fine-grained tokens** > **Generate new token** :
   propriétaire = votre compte, **Only select repositories** =
   `heywatchlist-certificates`, permission **Contents : Read and write**,
   expiration d'un an (notez la date : un jeton expiré fait échouer match avec
   « Error cloning certificates repo »). Le jeton va dans `MATCH_GIT_TOKEN`.
   Le nom d'utilisateur envoyé à GitHub est le propriétaire du dépôt
   `hey-torrent-native` (`GITHUB_REPOSITORY_OWNER`) : le dépôt des certificats et
   le jeton doivent donc être dans le même compte.
3. Choisissez une longue phrase secrète pour `MATCH_PASSWORD` et gardez-la dans
   un gestionnaire de mots de passe : perdue, il faut recréer le certificat.

### 4. Créer les secrets et variables iPhone

Les sept entrées du tableau « iPhone » ci-dessus.

### 5. Lancer la configuration de la signature

Onglet **Actions** > **iOS - configurer la signature** > **Run workflow** (branche
`main`). Il utilise la clé d'API pour créer le certificat de distribution et le
profil App Store, puis les range **chiffrés** dans `heywatchlist-certificates`.
À faire une fois, puis seulement pour régénérer le profil (case
« Régénérer le profil »).

Un workflow lancé à la main n'apparaît dans l'onglet Actions que si son fichier
est déjà sur la branche par défaut : fusionnez d'abord la PR qui l'apporte.

### 6. Premier envoi TestFlight

Onglet **Actions** > **CI iOS** > **Run workflow** (case « Envoyer le build sur
TestFlight » cochée). Ensuite, chaque fusion dans `main` qui touche le cœur,
iOS, ses scripts ou Cargo envoie un nouveau build.

La CI n'attend pas la fin du traitement par Apple (elle coûterait des minutes
macOS) : l'étape se termine dès l'envoi réussi. Apple traite le build en 10 à 30
minutes. Puis, dans App Store
Connect > votre app > **TestFlight** > **Test interne** > **+** : créez un
groupe, ajoutez votre compte et le build. Installez l'app **TestFlight** sur
l'iPhone, connectez-vous avec le même identifiant Apple : HeyWatchlist
apparaît. Le numéro de build est le numéro d'exécution GitHub
(`GITHUB_RUN_NUMBER`) ; la version vient du `Cargo.toml` racine.

### 7. Android : le keystore de signature

Il faut un JDK (Temurin 17, ou celui d'Android Studio) pour `keytool` :

```sh
keytool -genkeypair -v -keystore heywatchlist-release.jks -storetype PKCS12 \
  -alias heywatchlist -keyalg RSA -keysize 2048 -validity 10000
```

Choisissez un mot de passe (il sert aussi pour la clé) et répondez aux
questions. Puis encodez le fichier en base64 et collez le résultat dans
`ANDROID_KEYSTORE_BASE64` :

| Système | Commande |
|---|---|
| Linux | `base64 -w0 heywatchlist-release.jks` |
| macOS | `base64 -i heywatchlist-release.jks` |
| Windows (PowerShell) | `[Convert]::ToBase64String([IO.File]::ReadAllBytes("heywatchlist-release.jks"))` |

`ANDROID_KEYSTORE_PASSWORD` et `ANDROID_KEY_PASSWORD` = le mot de passe,
`ANDROID_KEY_ALIAS` = `heywatchlist`. **Ne commitez jamais le `.jks`** et
sauvegardez-le avec ses mots de passe hors du dépôt : sans lui, l'APK admin
installé ne peut plus être mis à jour sans désinstallation.

Pourquoi cette étape vaut la peine : l'APK de **debug** est signé par une clé
que Gradle crée à neuf sur chaque machine de la CI. Chaque nouvel APK de debug
a donc une signature différente du précédent, et Android refuse de l'installer
par-dessus (« application non installée ») : il faut désinstaller l'ancien
d'abord, et ses données disparaissent. L'APK **signé** avec votre keystore se
met à jour sur place.

## Lire les résultats

Onglet **Actions** > cliquer l'exécution > section **Artifacts**, tout en bas
de la page de résumé > télécharger le zip.

| Artefact | Contenu | Conservé |
|---|---|---|
| `heywatchlist-admin-release-apk` | APK admin signé, **seulement sans App Distribution** (`main`, tag ou lancement manuel) | 7 jours |
| `heywatchlist-public-release-apk` | APK public signé, à installer directement sur un téléphone | 30 jours |
| `heywatchlist-public-release-aab` | AAB public signé, pour le Play Store | 30 jours |
| `android-rapports-de-tests` | Rapports de tests, **en cas d'échec** | 7 jours |
| `ios-resultat-des-tests` | Résultat de tests (`.xcresult`), **en cas d'échec** | 7 jours |

Pour installer un APK : copiez-le sur le téléphone, ouvrez-le et autorisez
l'installation depuis cette source. Un APK de debug et un APK signé n'ont pas la
même signature, et deux APK de debug de deux exécutions différentes non plus :
désinstallez l'ancien avant d'installer le nouveau (voir l'étape 7 pour éviter
cela). La CI ne livre l'édition publique qu'en AAB (pour le Play Store) : l'APK à
installer chez soi est celui de l'édition **admin** (« HeyWatchlist Admin »,
identifiant en `….admin`).

L'iPhone n'a pas d'artefact : le build arrive dans l'app TestFlight. En cas
d'échec iOS, le journal complet de `xcodebuild` est dans l'étape en erreur de
l'exécution (sans Mac, c'est lui qui explique l'échec).

Le lancement manuel (**Run workflow**) de `CI Android` a une case « Produire
aussi les APK signés et l'AAB public » et une case « Envoyer aussi l'AAB sur
Google Play » (piste test fermé ; la version de `Cargo.toml` ne doit jamais
avoir été envoyée) ; celui de `CI iOS`, une case pour
envoyer ou non sur TestFlight.

## Coût des minutes macOS

Dépôt privé, offre gratuite : 2 000 minutes par mois (à confirmer dans
**Settings** > **Billing** de votre compte). Une minute macOS en compte **10**,
une minute Linux 1 : il reste environ **200 minutes macOS** par mois.

Estimation, **non mesurée** : un passage complet de `CI iOS` (compilation du
cœur, tests, archive, envoi) dure 12 à 25 minutes réelles, soit 120 à 250
minutes décomptées, donc environ 8 à 16 passages gratuits par mois. Le premier
est le plus long (cache vide). Pour économiser :

- le workflow ne tourne que si le cœur, `ios/`, les scripts d'iOS ou Cargo
  changent (pas pour un `.md`), et pas sur les PR en brouillon : passez la PR
  en « Ready for review » en dernier ;
- les PR hebdomadaires de Dependabot qui touchent `Cargo.lock` lancent `CI iOS`
  (macOS) : relevez-les en groupe plutôt qu'une à une ;
- tests et envoi TestFlight se font dans un seul job (une seule machine) ;
- n'utilisez pas « Re-run jobs » pour un envoi : le numéro de build ne change
  pas et Apple refuse un numéro déjà reçu. Relancez par **Run workflow**.

En principe la limite de dépense est à 0 $ : une fois le quota épuisé, les jobs
sont refusés, pas facturés. Vérifiez-le dans **Billing** > **Budgets and
alerts** avant de relever cette limite.

Si une PR est protégée par des « contrôles obligatoires », un workflow ignoré à
cause du filtre de chemins reste « en attente » : ne rendez obligatoires que les
contrôles qui tournent toujours, ou aucun.

## Dépannage

| Message | Cause et remède |
|---|---|
| Notice « Envoi ignoré : secrets ou variables absents » | Il manque le ou les noms listés : les créer (voir plus haut). Les tests, eux, ont tourné. |
| match : « readonly » ou aucun certificat trouvé | L'étape 5 n'a pas été faite, ou a échoué : relancer **iOS - configurer la signature**. |
| « The bundle version must be higher » (Apple) | Numéro de build déjà reçu : relancer par **Run workflow**, pas « Re-run jobs ». |
| Google Play : « ce numéro de version a déjà été envoyé » | Monter la version dans `Cargo.toml` (le `versionCode` en découle), puis nouveau tag. |
| Google Play : « app introuvable » | L'app n'existe pas encore dans la Play Console, ou son premier AAB n'a pas été déposé à la main (`docs/PUBLICATION.md`, étape 6). |
| Google Play : « The caller does not have permission » | Le compte de service n'est pas invité dans la Play Console, ou sans les autorisations des canaux de test (`docs/PUBLICATION.md`, étape 7). |
| Google Play : avertissement « envoyée en brouillon » | L'app n'a jamais été publiée : finir les déclarations et publier la version depuis la Play Console. Les envois suivants partent directement. |
| « Bundle ID not found » / app introuvable | Étape 2.2 ou 2.3 manquante, ou identifiant différent de `ios/project.yml`. |
| Avertissement « NDK … absent de l'image : repli sur … » | L'image Linux a changé de NDK par défaut : la CI continue avec celui de l'image ; mettre à jour `NDK_VERSION` dans `.github/actions/setup-android/action.yml` pour le faire disparaître. |
| « Error cloning certificates repo » (match) | `MATCH_GIT_URL` fausse, ou `MATCH_GIT_TOKEN` expiré, sans accès **Contents** en écriture au dépôt des certificats, ou d'un autre compte que le propriétaire de `hey-torrent-native`. |
| Erreur de signature ou de profil pendant l'archive | Le profil ne correspond pas à l'identifiant de `ios/project.yml` (changé depuis l'étape 5), ou à une capacité ajoutée depuis : relancer **iOS - configurer la signature** avec « Régénérer le profil ». |
| Envoi refusé : « agreement » / « contrat » à accepter | Un accord Apple est en attente : connectez-vous à App Store Connect et à developer.apple.com avec le compte titulaire, acceptez-le, puis relancez par **Run workflow**. |
| `fastlane` introuvable ou « Bundler … » à l'installation des gems | Le cache des gems est corrompu : changer la clé dans `.github/actions/setup-fastlane/action.yml` (ou supprimer le cache dans **Actions** > **Caches**). |
| « Gradle Wrapper Validation Failed » | `android/gradle/wrapper/gradle-wrapper.jar` n'est pas un fichier officiel de Gradle : le régénérer avec `gradle wrapper`, ne jamais le modifier à la main. |
| « Aucun simulateur iPhone disponible » | L'image macOS n'a aucun iPhone installé : ajouter `IOS_TEST_DEVICE` (nom d'un iPhone présent dans le README de l'image) dans `env:` de `ci-ios.yml`. Sans cette variable, la lane prend le premier iPhone du runtime le plus récent. |
| Libellé `xcode-27` refusé (« no runner matching ») | L'image Xcode 27 n'est pas offerte à ce dépôt ou à cette offre : repasser `runs-on` sur `macos-26` (Xcode 26, SDK iOS 26.5, suffisant jusqu'en avril 2027). |

## Versions utilisées

Le 2026-11-25, le `action.yml` de chacune de ces actions a été lu à la version
indiquée (elle existe donc) : `actions/checkout@v7`, `actions/setup-java@v6`,
`actions/upload-artifact@v7`, `actions/cache@v5`, `Swatinem/rust-cache@v2`,
`gradle/actions/setup-gradle@v6` (cache « basic », open source ; contrôle des
`gradle-wrapper.jar` activé par défaut), `maxim-lobanov/setup-xcode@v1`. Toutes
tournent sur Node 24. `cargo-ndk` 4.1.2 existe sur crates.io et se compile avec
le Rust du dépôt.

Les machines sont celles du README des images de GitHub (lu le 2026-11-25) :

| Machine | Image | Ce qu'elle apporte |
|---|---|---|
| `xcode-27` (arm64, macOS 27) | à relever dans « Set up job » du premier tour | Xcode 27 et SDK iOS 27 ; simulateurs, Ruby et fastlane non vérifiés (la lane de tests choisit l'iPhone au lieu de supposer un nom) |
| `macos-26` (arm64), encore utilisée par `ios-setup-signing.yml` | 20261117 | Xcode 26.6 par défaut (SDK iOS 26.5), simulateurs iPhone 17, Ruby 3.4, Bundler 4.1, fastlane 2.242.0 déjà installé (la CI utilise celui du `Gemfile.lock`), rustup, pas de XcodeGen (installé par Homebrew) |
| `ubuntu-24.04` | 20261116 | Java 17 par défaut, SDK Android avec plateforme 36 et build-tools 36.0.0, NDK 27.3.13750724 par défaut, shellcheck 0.9.0, rustup |

Elles sont fixées pour qu'un changement d'image ne casse pas la CI sans prévenir.
Dependabot propose les mises à jour (`.github/dependabot.yml`).

Non vérifié : la disponibilité du libellé `xcode-27` (annoncé par GitHub le 2026-10-15, lu dans son journal des changements) et de `macos-26` pour un dépôt **privé** sur
votre offre (visible dans le journal du premier tour : « Set up job » > « Runner
Image »), et la compatibilité de `xcodegen` (Homebrew, dernière version) avec
`ios/project.yml`.
