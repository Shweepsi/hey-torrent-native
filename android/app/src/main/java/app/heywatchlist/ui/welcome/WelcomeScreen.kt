package app.heywatchlist.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.auth.requestGoogleCredential
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.HeyTextField
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Accueil : ce qu'est l'app (elle se connecte aux serveurs personnels d'un
 * proche), connexion Google, puis code d'invitation si le serveur le demande.
 */
@Composable
fun WelcomeScreen(state: AccountUiState.SignedOut, viewModel: AccountViewModel) {
    val context = LocalContext.current
    WelcomeContent(
        state = state,
        onGoogle = {
            viewModel.signInWithGoogle { clientId, nonce -> requestGoogleCredential(context, clientId, nonce) }
        },
        onSubmitInvitation = viewModel::submitInvitation,
        onCancelInvitation = viewModel::cancelInvitation,
    )
}

@Composable
internal fun WelcomeContent(
    state: AccountUiState.SignedOut,
    onGoogle: () -> Unit,
    onSubmitInvitation: (String) -> Unit,
    onCancelInvitation: () -> Unit,
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
                if (state.askInvitation) {
                    InvitationStep(state.busy, onSubmitInvitation, onCancelInvitation)
                } else {
                    HeyText(text = stringResource(R.string.welcome_title), style = HeyTheme.typography.title)
                    HeyText(text = stringResource(R.string.welcome_body), color = HeyTheme.colors.onBackgroundMuted)
                    HeyButton(
                        text = stringResource(if (state.busy) R.string.welcome_signing_in else R.string.welcome_google),
                        onClick = onGoogle,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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

@Composable
private fun InvitationStep(busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val submit = { if (code.isNotBlank() && !busy) onSubmit(code) }
    HeyText(text = stringResource(R.string.invitation_title), style = HeyTheme.typography.title)
    HeyText(text = stringResource(R.string.invitation_body), color = HeyTheme.colors.onBackgroundMuted)
    HeyTextField(
        value = code,
        onValueChange = { code = it.take(20) },
        placeholder = stringResource(R.string.invitation_placeholder),
        label = stringResource(R.string.invitation_label),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )
    HeyButton(
        text = stringResource(if (busy) R.string.welcome_signing_in else R.string.invitation_submit),
        onClick = submit,
        enabled = !busy && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
    HeyButton(
        text = stringResource(R.string.invitation_cancel),
        onClick = onCancel,
        kind = HeyButtonKind.Secondary,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}
