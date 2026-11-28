package app.heywatchlist.ui.main

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.heywatchlist.R
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.Title
import app.heywatchlist.core.WatchlistFailure
import app.heywatchlist.core.WatchlistGateway
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.EnterMotion
import app.heywatchlist.ui.components.GlassTab
import app.heywatchlist.ui.components.GlassTabBar
import app.heywatchlist.ui.components.HeyIcons
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
import app.heywatchlist.ui.watchlist.WatchlistScreen
import app.heywatchlist.ui.watchlist.WatchlistViewModel

/** Les trois onglets de l'app. */
enum class Tab(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Discover(R.string.tab_discover, HeyIcons.Compass),
    Watchlist(R.string.tab_watchlist, HeyIcons.Bookmark),
    Settings(R.string.tab_settings, HeyIcons.Sliders),
}

/**
 * Onglets Découvrir, Watchlist et Réglages, avec la barre en verre en bas. Les
 * écrans ouverts depuis Découvrir (recherche, fiche, genre) la remplacent en
 * plein écran. Découvrir garde ses données (son ViewModel vit avec l'activité) et
 * sa position de défilement (états sauvegardés par onglet) quand on change
 * d'onglet ou qu'on ouvre une fiche.
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

    val top = stack.lastOrNull()
    // Sens du dernier mouvement de la pile : un écran ouvert glisse depuis la
    // droite, un retour fait réapparaître l'écran du dessous.
    var lastDepth by remember { mutableIntStateOf(stack.size) }
    val forward = stack.size > lastDepth
    SideEffect { lastDepth = stack.size }
    val animations = rememberAnimationsEnabled()
    if (top != null) {
        BackHandler { navigator.back() }
        key(top.id) {
            EnterMotion(forward = forward) {
                savedStates.SaveableStateProvider(entryStateKey(top.id)) {
                    DiscoverEntryScreen(entry = top, catalog = catalog, watchlist = watchlist, navigator = navigator)
                }
            }
        }
    } else {
        EnterMotion(forward = false) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        if (animations) {
                            fadeIn(tween(durationMillis = 220, delayMillis = 60)) togetherWith fadeOut(tween(durationMillis = 120))
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "tab",
                ) { shown ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        savedStates.SaveableStateProvider(shown.name) {
                            when (shown) {
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
                TabBar(selected = tab, onSelect = { tab = it })
            }
        }
    }
}

/** Clé sous laquelle l'état sauvegardé d'un écran de la pile est rangé. */
private fun entryStateKey(id: String) = "entry-$id"

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
            val model: SearchViewModel = viewModel(
                viewModelStoreOwner = entry,
                factory = remember(catalog) { SearchViewModel.factory(catalog) },
            )
            SearchScreen(viewModel = model, onBack = onBack, onOpenTitle = onOpenTitle)
        }

        is DiscoverDestination.Detail -> {
            val model: DetailViewModel = viewModel(
                viewModelStoreOwner = entry,
                factory = remember(catalog, destination) { DetailViewModel.factory(catalog, destination.title) },
            )
            DetailScreen(viewModel = model, watchlist = watchlist, onBack = onBack, onOpenTitle = onOpenTitle)
        }

        is DiscoverDestination.GenreTitles -> {
            val model: GenreViewModel = viewModel(
                viewModelStoreOwner = entry,
                factory = remember(catalog, destination) { GenreViewModel.factory(catalog, destination.genre) },
            )
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
internal fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    GlassTabBar(
        tabs = Tab.entries.map { GlassTab(label = stringResource(it.labelRes), icon = it.icon) },
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(Tab.entries[it]) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
