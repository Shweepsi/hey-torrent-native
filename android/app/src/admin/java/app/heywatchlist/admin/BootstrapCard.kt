package app.heywatchlist.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.AccountGateway
import app.heywatchlist.core.Profile
import app.heywatchlist.core.UniffiAccountGateway
import app.heywatchlist.core.toProfile
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.HeyTextField
import app.heywatchlist.ui.theme.HeyTheme
import kotlinx.coroutines.launch
import uniffi.heywatchlist_core.AccountException

/**
 * Premier déploiement du serveur : le code d'amorçage (gardé dans hey-kit)
 * fait de ce compte en attente l'administrateur. Sans effet ensuite.
 */
@Composable
fun BootstrapCard(gateway: AccountGateway, onProfileChanged: (Profile) -> Unit) {
    val uniffi = gateway as? UniffiAccountGateway ?: return
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<Int?>(null) }
    fun submit() {
        if (code.isBlank() || submitting) return
        submitting = true
        errorText = null
        scope.launch {
            try {
                val account = uniffi.account()
                val profile = uniffi.call { account.claimAdmin(code) }.toProfile()
                onProfileChanged(profile)
            } catch (error: AccountFailureException) {
                errorText = if (error.cause is AccountException.Forbidden) {
                    R.string.admin_bootstrap_refused
                } else {
                    error.failure.messageRes
                }
            } finally {
                submitting = false
            }
        }
    }
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeyText(text = stringResource(R.string.admin_bootstrap_title), style = HeyTheme.typography.title)
            HeyText(text = stringResource(R.string.admin_bootstrap_body), color = HeyTheme.colors.onBackgroundMuted)
            HeyTextField(
                value = code,
                onValueChange = { code = it.take(20) },
                placeholder = stringResource(R.string.admin_bootstrap_placeholder),
                label = stringResource(R.string.admin_bootstrap_label),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            HeyButton(
                text = stringResource(if (submitting) R.string.admin_bootstrap_submitting else R.string.admin_bootstrap_submit),
                onClick = { submit() },
                kind = HeyButtonKind.Secondary,
                enabled = !submitting && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            errorText?.let { message ->
                HeyText(
                    text = stringResource(message),
                    color = HeyTheme.colors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
