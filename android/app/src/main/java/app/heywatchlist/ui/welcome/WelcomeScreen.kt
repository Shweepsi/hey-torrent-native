package app.heywatchlist.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.auth.requestGoogleCredential
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyIcons
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
    val posters by viewModel.welcomePosters.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadWelcomePosters() }
    WelcomeContent(
        state = state,
        posters = posters,
        onGoogle = {
            viewModel.signInWithGoogle { clientId, nonce -> requestGoogleCredential(context, clientId, nonce) }
        },
    )
}

@Composable
internal fun WelcomeContent(
    state: AccountUiState.SignedOut,
    onGoogle: () -> Unit,
    posters: List<String> = emptyList(),
) {
    WelcomeLayout(posters = posters) {
        LogoMark()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HeyText(
                text = stringResource(R.string.app_name),
                style = HeyTheme.typography.display,
                modifier = Modifier.semantics { heading() },
            )
            HeyText(
                text = stringResource(R.string.welcome_tagline),
                style = HeyTheme.typography.title,
                color = HeyTheme.colors.onBackgroundMuted,
            )
        }
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FeatureRow(icon = HeyIcons.Compass, text = stringResource(R.string.welcome_feature_discover))
                FeatureRow(icon = HeyIcons.Plus, text = stringResource(R.string.welcome_feature_request))
                FeatureRow(icon = HeyIcons.Bell, text = stringResource(R.string.welcome_feature_notify))
                HeyText(
                    text = stringResource(R.string.welcome_access_note),
                    style = HeyTheme.typography.caption,
                    color = HeyTheme.colors.onBackgroundMuted,
                )
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
