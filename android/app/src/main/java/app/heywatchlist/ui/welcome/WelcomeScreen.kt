package app.heywatchlist.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.auth.requestGoogleCredential
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Accueil : ce qu'est l'app (elle se connecte aux serveurs personnels d'un
 * proche) et connexion Google. Un nouveau compte attend ensuite d'être
 * accepté ([WaitingScreen]).
 */
@Composable
fun WelcomeScreen(state: AccountUiState.SignedOut, viewModel: AccountViewModel) {
    val context = LocalContext.current
    WelcomeContent(
        state = state,
        onGoogle = {
            viewModel.signInWithGoogle { clientId, nonce -> requestGoogleCredential(context, clientId, nonce) }
        },
    )
}

@Composable
internal fun WelcomeContent(
    state: AccountUiState.SignedOut,
    onGoogle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        HeyText(
            text = stringResource(R.string.app_name),
            style = HeyTheme.typography.display,
            modifier = Modifier.semantics { heading() },
        )
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeyText(text = stringResource(R.string.welcome_title), style = HeyTheme.typography.title)
                HeyText(text = stringResource(R.string.welcome_body), color = HeyTheme.colors.onBackgroundMuted)
                HeyButton(
                    text = stringResource(if (state.busy) R.string.welcome_signing_in else R.string.welcome_google),
                    onClick = onGoogle,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.errorRes?.let { message ->
                    HeyText(
                        text = stringResource(message),
                        color = HeyTheme.colors.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}
