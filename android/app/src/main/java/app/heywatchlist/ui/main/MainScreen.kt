package app.heywatchlist.ui.main

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.heywatchlist.R
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.Title
import app.heywatchlist.core.WatchlistFailure
import app.heywatchlist.core.WatchlistGateway
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.AuroraBackground
import app.heywatchlist.ui.components.GlassTab
import app.heywatchlist.ui.components.GlassTabBar
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.LocalTabBarSpace
import app.heywatchlist.ui.components.rememberAnimationsEnabled
import app.heywatchlist.ui.discover.DetailScreen
import app.heywatchlist.ui.discover.DetailViewModel
import app.heywatchlist.ui.discover.DiscoverDestination
import app.heywatchlist.ui.discover.DiscoverEntry
import app.heywatchlist.ui.discover.DiscoverNavigator
import app.heywatchlist.ui.discover.DiscoverScreen
import app.heywatchlist.ui.discover.DiscoverViewModel
import app.heywatchlist.ui.discover.GenreScreen
import app.heywatchlist.ui.discover.GenreViewModel
import app.heywatchlist.ui.discover.SearchScreen
import app.heywatchlist.ui.discover.SearchViewModel
import app.heywatchlist.ui.settings.SettingsScreen
import app.heywatchlist.ui.theme.HeyTheme
import app.heywatchlist.ui.watchlist.WatchlistScreen
import app.heywatchlist.ui.watchlist.WatchlistViewModel

/** Les trois onglets de l'app. */
enum class Tab(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Discover(R.string.tab_discover, HeyIcons.Compass),
    Watchlist(R.string.tab_watchlist, HeyIcons.Bookmark),
    Settings(R.string.tab_settings, HeyIcons.Sliders),
}

/**
 * Onglets Découvrir, Watchlist et Réglages, avec la barre en verre flottante en
 * bas. Les écrans ouverts depuis Découvrir (recherche, fiche, genre) passent
 * par-dessus en plein écran. Découvrir garde ses données (son ViewModel vit avec
 * l'activité) et sa position de défilement (états sauvegardés par onglet) quand
 * on change d'onglet ou qu'on ouvre une fiche.
 *
 * Transitions sans clignotement : l'écran du dessus est toujours opaque, et
 * celui du dessous reste affiché tant qu'il bouge. À l'ouverture, le nouvel
 * écran glisse et apparaît par-dessus l'ancien ; au retour, l'écran quitté
 * glisse et s'efface en découvrant celui du dessous, déjà en place. Jamais de
 * passage par le décor seul entre les deux.
 */
@Composable
fun MainScreen(
    state: AccountUiState.SignedIn,
    accountViewModel: AccountViewModel,
    catalog: CatalogGateway,
    watchlistGateway: WatchlistGateway,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Discover) }
    val savedStates = rememberSaveableStateHolder()
    val navigator: DiscoverNavigator = viewModel(factory = DiscoverNavigator.Factory)
    val discoverFactory = remember(catalog) { DiscoverViewModel.factory(catalog) }
    val discover: DiscoverViewModel = viewModel(factory = discoverFactory)
    // Partagée par l'onglet et les fiches : un ajout se voit partout.
    val watchlist: WatchlistViewModel = viewModel(factory = remember(watchlistGateway) { WatchlistViewModel.factory(watchlistGateway) })
    val stack by navigator.stack.collectAsStateWithLifecycle()
    // Après une reconnexion ou une rotation, ce qui avait échoué se relit.
    LaunchedEffect(discover) { discover.reloadFailed() }
    // À chaque arrivée sur les onglets (connexion, retour de l'app) : copie locale, puis synchronisation.
    LaunchedEffect(watchlist) { watchlist.open() }
    WatchlistMessages(watchlist)
    // Un écran quitté ne garde pas son état sauvegardé (défilement, saisie) : sans cela il s'accumulerait.
    val openIds = remember { mutableSetOf<String>() }
    LaunchedEffect(stack) {
        val ids = stack.mapTo(mutableSetOf()) { it.id }
        (openIds - ids).forEach { savedStates.removeState(entryStateKey(it)) }
        openIds.clear()
        openIds.addAll(ids)
    }

    if (stack.isNotEmpty()) BackHandler { navigator.back() }
    val animations = rememberAnimationsEnabled()
    AnimatedContent(
        targetState = Shown(stack.lastOrNull(), stack.size),
        contentKey = { it.entry?.id },
        transitionSpec = { stackTransition(forward = targetState.depth > initialState.depth, animations = animations) },
        label = "stack",
    ) { shown ->
        val entry = shown.entry
        if (entry != null) {
            savedStates.SaveableStateProvider(entryStateKey(entry.id)) {
                OpaqueScreen {
                    DiscoverEntryScreen(entry = entry, catalog = catalog, watchlist = watchlist, navigator = navigator)
                }
            }
        } else {
            TabsScaffold(selected = tab, onSelect = { tab = it }) { shownTab ->
                savedStates.SaveableStateProvider(shownTab.name) {
                    when (shownTab) {
                        Tab.Discover -> DiscoverScreen(
                            viewModel = discover,
                            onOpenTitle = { navigator.open(DiscoverDestination.Detail(it)) },
                            onOpenSearch = { navigator.open(DiscoverDestination.Search) },
                            onOpenGenre = { navigator.open(DiscoverDestination.GenreTitles(it)) },
                        )

                        Tab.Watchlist -> WatchlistScreen(
                            viewModel = watchlist,
                            onOpenTitle = { navigator.open(DiscoverDestination.Detail(it)) },
                        )
                        Tab.Settings -> SettingsScreen(state = state, viewModel = accountViewModel)
                    }
                }
            }
        }
    }
}

/** Ce que montre la pile : son écran du dessus (aucun : les onglets) et sa profondeur, pour le sens du mouvement. */
private data class Shown(val entry: DiscoverEntry?, val depth: Int)

/**
 * Ouverture : le nouvel écran glisse un peu depuis la droite en apparaissant,
 * l'ancien reste en place dessous jusqu'à la fin. Retour : l'écran quitté
 * repasse au premier plan, glisse et s'efface ; celui du dessous est déjà là.
 */
private fun stackTransition(forward: Boolean, animations: Boolean): ContentTransform {
    if (!animations) return EnterTransition.None togetherWith ExitTransition.None
    val slide = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
    return if (forward) {
        (slideInHorizontally(slide) { it / SLIDE_FRACTION } + fadeIn(tween(ENTER_MILLIS))) togetherWith
            fadeOut(snap(delayMillis = HOLD_MILLIS))
    } else {
        (EnterTransition.None togetherWith (slideOutHorizontally(slide) { it / SLIDE_FRACTION } + fadeOut(tween(EXIT_MILLIS))))
            .apply { targetContentZIndex = -1f }
    }
}

private const val SLIDE_FRACTION = 8
private const val ENTER_MILLIS = 220
private const val EXIT_MILLIS = 200

/** L'écran du dessous reste affiché jusqu'à ce que le nouveau soit en place. */
private const val HOLD_MILLIS = 320

/**
 * Écran de la pile, posé sur son propre décor opaque : pendant une transition,
 * on ne voit jamais l'écran du dessous au travers.
 */
@Composable
private fun OpaqueScreen(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(HeyTheme.colors.background)) {
        AuroraBackground(Modifier.fillMaxSize())
        content()
    }
}

/**
 * Les onglets : l'écran choisi occupe tout l'écran, bord à bord, et la barre
 * d'onglets flotte en bas par-dessus, au-dessus de la barre système. L'écran
 * réserve sa place par [LocalTabBarSpace]. Un changement d'onglet est immédiat,
 * avec un court fondu d'arrivée du nouvel écran (l'ancien disparaît aussitôt :
 * pas de superposition ni de passage par un écran vide).
 */
@Composable
internal fun TabsScaffold(selected: Tab, onSelect: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Fondu seulement quand on change d'onglet, pas quand les onglets réapparaissent sous une fiche qu'on quitte.
        val previous = remember { mutableStateOf(selected) }
        val switched = previous.value != selected
        SideEffect { previous.value = selected }
        CompositionLocalProvider(LocalTabBarSpace provides TabBarSpace) {
            key(selected) {
                FadeIn(enabled = switched) { content(selected) }
            }
        }
        TabBar(
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

/** Court fondu d'arrivée si [enabled] ; sans animations système, l'écran est là tout de suite. */
@Composable
private fun FadeIn(enabled: Boolean, content: @Composable () -> Unit) {
    val animations = rememberAnimationsEnabled()
    val alpha = remember { Animatable(if (animations && enabled) 0f else 1f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(TAB_FADE_MILLIS)) }
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) { content() }
}

private const val TAB_FADE_MILLIS = 150

/** Barre d'onglets (56 dp + 12 dp de marge intérieure) et ses marges de 8 dp. */
private val TabBarSpace = 84.dp

/** Clé sous laquelle l'état sauvegardé d'un écran de la pile est rangé. */
private fun entryStateKey(id: String) = "entry-$id"

/**
 * ViewModel d'une étape de la pile, lu une fois : l'écran quitté reste affiché
 * le temps de sa sortie, alors que la pile a déjà vidé ses ViewModels ; le
 * redemander créerait un ViewModel neuf (et une nouvelle lecture).
 */
private inline fun <reified T : ViewModel> entryViewModel(entry: DiscoverEntry, factory: ViewModelProvider.Factory): T =
    ViewModelProvider.create(entry, factory)[T::class]

/** Un écran de la pile de Découvrir, avec les ViewModels de son étape. */
@Composable
private fun DiscoverEntryScreen(
    entry: DiscoverEntry,
    catalog: CatalogGateway,
    watchlist: WatchlistViewModel,
    navigator: DiscoverNavigator,
) {
    val onBack = { navigator.back() }
    val onOpenTitle = { title: Title -> navigator.open(DiscoverDestination.Detail(title)) }
    when (val destination = entry.destination) {
        DiscoverDestination.Search -> {
            val model = remember(entry, catalog) { entryViewModel<SearchViewModel>(entry, SearchViewModel.factory(catalog)) }
            SearchScreen(viewModel = model, onBack = onBack, onOpenTitle = onOpenTitle)
        }

        is DiscoverDestination.Detail -> {
            val model = remember(entry, catalog) { entryViewModel<DetailViewModel>(entry, DetailViewModel.factory(catalog, destination.title)) }
            DetailScreen(viewModel = model, watchlist = watchlist, onBack = onBack, onOpenTitle = onOpenTitle)
        }

        is DiscoverDestination.GenreTitles -> {
            val model = remember(entry, catalog) { entryViewModel<GenreViewModel>(entry, GenreViewModel.factory(catalog, destination.genre)) }
            GenreScreen(genre = destination.genre, viewModel = model, onBack = onBack, onOpenTitle = onOpenTitle)
        }
    }
}

/** Un échec de la watchlist (titre refusé, erreur imprévue) s'affiche une fois, en bref. */
@Composable
private fun WatchlistMessages(watchlist: WatchlistViewModel) {
    val context = LocalContext.current
    val state by watchlist.uiState.collectAsStateWithLifecycle()
    val message = state.message
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        // La session expirée ramène déjà à l'accueil : pas de message en plus.
        if (message != WatchlistFailure.SignedOut) {
            Toast.makeText(context, context.getString(message.messageRes), Toast.LENGTH_SHORT).show()
        }
        watchlist.consumeMessage()
    }
}

@Composable
internal fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    GlassTabBar(
        tabs = Tab.entries.map { GlassTab(label = stringResource(it.labelRes), icon = it.icon) },
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(Tab.entries[it]) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
