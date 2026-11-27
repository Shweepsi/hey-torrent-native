package app.heywatchlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.heywatchlist.appServices
import app.heywatchlist.core.Access
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.WatchlistGateway
import app.heywatchlist.diagnostics.CrashReport
import app.heywatchlist.diagnostics.CrashReportPanel
import app.heywatchlist.push.PushRegistrationEffect
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.AuroraBackground
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.main.MainScreen
import app.heywatchlist.ui.theme.HeyTheme
import app.heywatchlist.ui.welcome.WaitingScreen
import app.heywatchlist.ui.welcome.WelcomeScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    val hazeState = rememberHazeState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Lu tout de suite (petit fichier) : l'app reste en pause tant que le rapport
    // est affiché, sinon un plantage immédiat empêcherait de le copier.
    var crashReport by remember { mutableStateOf(runCatching { CrashReport.pending(context) }.getOrNull()) }
    Box(modifier = Modifier.fillMaxSize().background(HeyTheme.colors.background)) {
        // Le décor est la source du flou ; les surfaces en verre sont ses voisines.
        AuroraBackground(Modifier.fillMaxSize().hazeSource(hazeState))
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            val report = crashReport
            if (report != null) {
                // Après un plantage : le rapport passe devant tout, pour être copié.
                CrashReportPanel(report = report, onDismiss = {
                    crashReport = null
                    scope.launch(Dispatchers.IO) { runCatching { CrashReport.dismiss(context) } }
                })
            } else when (val current = state) {
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
}
