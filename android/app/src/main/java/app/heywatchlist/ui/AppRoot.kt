package app.heywatchlist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.heywatchlist.appServices
import app.heywatchlist.core.Access
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.WatchlistGateway
import app.heywatchlist.push.PushRegistrationEffect
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.AppBackdrop
import app.heywatchlist.ui.main.MainScreen
import app.heywatchlist.ui.welcome.WaitingScreen
import app.heywatchlist.ui.welcome.WelcomeScreen

/**
 * Racine de l'interface : accueil tant qu'on n'est pas connecté, écran
 * d'attente tant que l'accès n'est pas accepté, sinon les onglets.
 */
@Composable
fun AppRoot(
    viewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
    catalog: CatalogGateway = LocalContext.current.appServices.catalog,
    watchlist: WatchlistGateway = LocalContext.current.appServices.watchlist,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppBackdrop {
        when (val current = state) {
            // Lecture locale très courte : on garde le décor seul.
            AccountUiState.Starting -> Unit
            is AccountUiState.SignedOut -> WelcomeScreen(state = current, viewModel = viewModel)
            // L'appareil s'enregistre aussi pendant l'attente : la personne
            // est prévenue quand son accès est accepté.
            is AccountUiState.SignedIn -> {
                PushRegistrationEffect()
                if (current.profile.access != Access.Active) {
                    WaitingScreen(state = current, viewModel = viewModel)
                } else {
                    MainScreen(
                        state = current,
                        accountViewModel = viewModel,
                        catalog = catalog,
                        watchlistGateway = watchlist,
                    )
                }
            }
        }
    }
}
