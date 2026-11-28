# Identité visuelle

À lire **avant toute interface**. Référence : `context_hey-torrent.md` §1.2 et
§7.1. On ne reproduit l'apparence d'aucune app existante : on vise la
simplicité, la stabilité et un style propre à HeyWatchlist.

## Principe : le verre dépoli

Des **surfaces translucides et floutées** (barre d'onglets, barres de
navigation, tiroirs, fenêtres modales, cartes posées sur une affiche) flottent
au-dessus de **fonds riches** : affiches et fonds TMDB floutés, dégradés
doux. Le verre ne vaut que par ce qu'il y a derrière : sur un fond uni, il ne se
voit pas.

- Coins très arrondis, fines bordures claires, ombres légères.
- Typographie nette ; grands titres.
- **Sombre d'abord** : le thème sombre est la référence, le clair en est la
  déclinaison.
- Les couleurs ne portent jamais seules une information (état d'une demande :
  texte et icône en plus de la teinte).

## Jetons

Valeurs communes aux deux plateformes.

| Jeton | Valeur | Usage |
|---|---|---|
| `primary` | `#4f9cf7` | Accent : actions, onglet actif, liens |
| `background` | `#0c0c0f` | Fond de l'app (sombre) |
| `glassFill` | `rgba(255,255,255,0.09)` + flou | Remplissage du verre (sombre) |
| `glassBorder` | `rgba(255,255,255,0.2)`, 1 px | Filet du verre (sombre) |

Compléments aujourd'hui dans le code Android (`HeyTheme.kt`) : texte
`#f5f5f8`, texte secondaire à 72 % d'opacité, texte sur `primary` en
`#0c0c0f`, erreur `#ff8a80`, accent décoratif `#7c5cff`, formes à 28 dp
(cartes) et 20 dp (boutons).

**Thème clair** : décliné côté Android (fond `#f3f5fa`, `primary` `#1a66cc`,
verre blanc à 55 %, filet foncé à 12 %). Depuis la v0.11.0, l'app Android est
**claire partout**, fiche d'un titre comprise, quel que soit le réglage du
téléphone (choix du propriétaire) ; barres système transparentes (bord à
bord). Côté iPhone, l'app est pour l'instant forcée en sombre (voir
`docs/TODO.md`).

**Hiérarchie des boutons** : une seule action pleine (`primary`) par écran ou
par feuille ; les autres en verre (`Secondary`) ou en lien (`Link`,
`DangerLink` pour retirer). Les résultats d'une action s'affichent en
`success` ou `error` avec une icône (`HeyStatusLine`), jamais en couleur
décorative. Les confirmations sont des feuilles qui montent du bas (`HeySheet`).

## Les trois onglets

Barre d'onglets en verre, identique pour tout le monde :
**Découvrir · Watchlist · Réglages** (« Réglages » : le mot français d'iOS).

| Onglet | Rôle | Priorité |
|---|---|---|
| Découvrir | Écran d'accueil et le plus soigné : carrousel, rangées (tendances, nouveautés, à venir, par genre), recherche instantanée, fiche détail plein écran. Badge « Sur Plex » | 1 |
| Watchlist | Ce que je veux voir, avec l'état de chaque demande (demandée, en cours, disponible, refusée) ; ajout en un geste depuis toute affiche | 2 |
| Réglages | Compte, notifications, « À propos » (attribution TMDB), conditions et confidentialité, déconnexion, suppression du compte | 3 |

L'espace admin n'ajoute **pas** d'onglet : il vit uniquement dans l'APK admin
Android, jamais dans les apps publiques (voir `docs/DECISIONS.md`).

L'onglet Découvrir est construit (jalon 3) ; Watchlist reste un espace
réservé jusqu'au jalon 4.

## iPhone : Liquid Glass natif (iOS 26)

- Les matériaux d'Apple, **jamais un flou recréé à la main** : `.glassEffect`,
  `GlassEffectContainer` pour regrouper les surfaces voisines.
- Barre d'onglets flottante qui se rétracte au défilement
  (`tabBarMinimizeBehavior`) ; **recherche dans une bulle séparée** à droite
  des onglets (`Tab` avec le rôle `.search`).
- Fiches en plein écran, l'affiche s'agrandit depuis la liste.
- Human Interface Guidelines : cibles tactiles d'au moins 44 pt, retour
  haptique, Dynamic Type.
- iOS 26 étant le minimum, aucun repli pour les versions antérieures.

## Android : `HeyTheme` et Haze

- **Pas de Material 3.** Le thème maison `HeyTheme` (couleurs sombres et
  claires, typographie, formes, une dizaine de composants) est construit sur
  Compose Foundation et Compose UI. La dépendance `material3` est interdite.
- Flou par la bibliothèque **Haze** (Android 12 minimum : le flou est
  disponible partout). Une surface de verre déclare une source de flou ; sans
  elle, ou quand le flou est coupé, elle devient **translucide plus opaque, sans
  flou** (`glassFillFallback`).
- Comportements d'Android conservés : bouton et geste retour, barres système,
  accessibilité, cibles tactiles d'au moins 48 dp.

## Règles de lisibilité

1. **Contraste WCAG AA** pour le texte sur verre, en sombre comme en clair
   (4,5:1 pour le texte courant, 3:1 pour le gros texte). Avec les jetons
   sombres, le texte `#f5f5f8` donne environ 18:1 sur le fond et 14,6:1 sur une
   surface de verre unie ; `primary` donne 6,9:1 sur le fond (calcul WCAG des
   rapports de luminance, hors flou d'image).
2. **Texte sur `primary`** : `#0c0c0f` (6,9:1). Le blanc ne donne que 2,8:1 :
   à ne pas utiliser.
3. **Texte sur affiche** : jamais directement sur une image. Poser un dégradé
   sombre ou une surface de verre assez opaque ; une affiche claire sous un
   verre à 9 % est illisible. Vérifier les affiches les plus claires.
4. **Respecter les réglages système** « Réduire la transparence » (iOS) et
   « Supprimer les animations » (Android) : le verre devient alors une surface
   **opaque** et les animations sont retirées.
5. **Batterie et fluidité** : le flou coûte cher. On le réserve aux surfaces
   fixes (barres, modales), **jamais à chaque élément d'une longue liste** qui
   défile. Viser 60 i/s minimum sur un appareil moyen.

## Mouvement

Transitions courtes et naturelles (ressorts), affiche qui s'agrandit vers la
fiche détail, squelettes de chargement animés dès qu'une recherche part. L'écran
ne gèle jamais.

## Mentions obligatoires

L'écran « À propos » porte le logo TMDB et la phrase d'attribution fournie par
TMDB (jalon 7). La section des plateformes de la fiche porte la mention
« Disponibilités fournies par JustWatch ». Aucune marque tierce (Plex, Netflix…) dans le nom, l'icône ou le
sous-titre ; aucun tracker nommé, nulle part.

## Où en est le code

| | iPhone | Android |
|---|---|---|
| Jetons de couleur | `HeyTheme.swift` : `primary`, `background` | `HeyTheme.kt` : tous les jetons ci-dessus, clair et sombre |
| Verre | Liquid Glass du système | `GlassSurface` (Haze) avec repli opaque |
| Fond | `HeyBackground` : dégradé sombre | `AppBackdrop` (`AuroraBackground` : halos) ; accueil et attente : `CinemaBackdrop` (mur d'affiches stylisées qui défile) ; fiche : affiche très floutée |
| Onglets | Barre système | `GlassTabBar` : icône et libellé, pastille qui glisse |
| États | | `StatusPill` : icône, texte et teinte (`success`, `warning`, `primary`, `error`) |
| Mouvement | | `EnterMotion` (écran ouvert ou retrouvé), `rememberPress` (enfoncement), fondus entre écrans et onglets |
| Découvrir | `DiscoverView` (carrousel, rangées, genres), `GenreView` | `DiscoverScreen`, `GenreScreen` |
| Recherche | `SearchView`, onglet `Tab(role: .search)` | `SearchScreen`, champ en tête de Découvrir |
| Fiche détail | `TitleDetailView` (transition zoom) | `DetailScreen` |
| Affiches | `PosterView` (`AsyncImage`) | `HeyPoster` (Coil) |

Au jalon 3, la barre d'onglets iPhone compte une bulle de recherche à côté des
trois onglets. Pas encore géré : les réglages « Réduire la transparence » et
« Supprimer les animations » dans tout l'app (règle 4, seulement là où les
nouveaux composants le faisaient simplement ; voir `docs/TODO.md`), le thème
clair d'iOS, la fiche et le carrousel jamais vus sur appareil (contraste des
affiches claires à contrôler). Côté Android, le repli opaque existe déjà quand
Haze ne peut pas flouter.

Les textes affichés sont dans `Localizable.xcstrings` (iOS) et `strings.xml`
(Android), jamais en dur.
