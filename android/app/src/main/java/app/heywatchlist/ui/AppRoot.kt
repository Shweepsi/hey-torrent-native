package app.heywatchlist.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import app.heywatchlist.ui.components.rememberAnimationsEnabled
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
    val animations = rememberAnimationsEnabled()
    // L'appareil s'enregistre aussi pendant l'attente : la personne est
    // prévenue quand son accès est accepté. Hors du fondu : une seule fois.
    if (state is AccountUiState.SignedIn) PushRegistrationEffect()
    AppBackdrop {
        // Fondu entre l'accueil, l'attente et les onglets ; pas entre deux états d'un même écran.
        AnimatedContent(
            targetState = state,
            contentKey = { it.screen },
            transitionSpec = {
                if (animations) {
                    fadeIn(tween(durationMillis = 320)) togetherWith fadeOut(tween(durationMillis = 200))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "root",
        ) { current ->
            RootScreen(current, viewModel, catalog, watchlist)
        }
    }
}

/** Les grands écrans de l'app, pour ne déclencher le fondu qu'en passant de l'un à l'autre. */
private val AccountUiState.screen: Int
    get() = when (this) {
        AccountUiState.Starting -> 0
        is AccountUiState.SignedOut -> 1
        is AccountUiState.SignedIn -> if (profile.access != Access.Active) 2 else 3
    }

@Composable
private fun RootScreen(
    state: AccountUiState,
    viewModel: AccountViewModel,
    catalog: CatalogGateway,
    watchlist: WatchlistGateway,
) {
    when (val current = state) {
        // Lecture locale très courte : on garde le décor seul.
        AccountUiState.Starting -> Unit
        is AccountUiState.SignedOut -> WelcomeScreen(state = current, viewModel = viewModel)
        is AccountUiState.SignedIn -> if (current.profile.access != Access.Active) {
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
