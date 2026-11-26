package app.heywatchlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.heywatchlist.appServices
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.WatchlistGateway
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.AuroraBackground
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.main.MainScreen
import app.heywatchlist.ui.theme.HeyTheme
import app.heywatchlist.ui.welcome.WelcomeScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Racine de l'interface : accueil tant qu'on n'est pas connecté, sinon les onglets. */
@Composable
fun AppRoot(
    viewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
    catalog: CatalogGateway = LocalContext.current.appServices.catalog,
    watchlist: WatchlistGateway = LocalContext.current.appServices.watchlist,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hazeState = rememberHazeState()
    Box(modifier = Modifier.fillMaxSize().background(HeyTheme.colors.background)) {
        // Le décor est la source du flou ; les surfaces en verre sont ses voisines.
        AuroraBackground(Modifier.fillMaxSize().hazeSource(hazeState))
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            when (val current = state) {
                // Lecture locale très courte : on garde le décor seul.
                AccountUiState.Starting -> Unit
                is AccountUiState.SignedOut -> WelcomeScreen(state = current, viewModel = viewModel)
                is AccountUiState.SignedIn -> MainScreen(
                    state = current,
                    accountViewModel = viewModel,
                    catalog = catalog,
                    watchlistGateway = watchlist,
                )
            }
        }
    }
}
