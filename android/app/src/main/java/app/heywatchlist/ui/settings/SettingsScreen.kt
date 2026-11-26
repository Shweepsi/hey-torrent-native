package app.heywatchlist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.heywatchlist.EditionContent
import app.heywatchlist.R
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/** Réglages : prénom du compte, déconnexion, suppression du compte, version. */
@Composable
fun SettingsScreen(state: AccountUiState.SignedIn, viewModel: AccountViewModel) {
    SettingsContent(
        state = state,
        onSignOut = viewModel::signOut,
        onDelete = viewModel::requestDeletion,
        onConfirmDelete = viewModel::confirmDeletion,
        onCancelDelete = viewModel::cancelDeletion,
        extras = { EditionContent.SettingsExtras(gateway = viewModel.gateway, isAdmin = state.profile.isAdmin) },
    )
}

@Composable
internal fun SettingsContent(
    state: AccountUiState.SignedIn,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    extras: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeyText(
            text = stringResource(R.string.tab_settings),
            style = HeyTheme.typography.display,
            modifier = Modifier.semantics { heading() },
        )
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val name = state.profile.firstName
                HeyText(
                    text = if (name != null) stringResource(R.string.settings_hello, name) else stringResource(R.string.settings_hello_anonymous),
                    style = HeyTheme.typography.title,
                )
                HeyText(
                    text = stringResource(if (state.profile.isAdmin) R.string.settings_role_admin else R.string.settings_role_member),
                    color = HeyTheme.colors.onBackgroundMuted,
                )
            }
        }
        extras()
        state.errorRes?.let { message ->
            HeyText(
                text = stringResource(message),
                color = HeyTheme.colors.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HeyButton(
            text = stringResource(R.string.settings_sign_out),
            onClick = onSignOut,
            kind = HeyButtonKind.Secondary,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        HeyButton(
            text = stringResource(R.string.settings_delete),
            onClick = onDelete,
            kind = HeyButtonKind.Danger,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.coreVersion.isNotEmpty()) {
            HeyText(
                text = stringResource(R.string.settings_version, state.coreVersion),
                color = HeyTheme.colors.onBackgroundMuted,
            )
        }
    }
    if (state.confirmingDeletion) {
        DeleteDialog(busy = state.busy, onConfirm = onConfirmDelete, onCancel = onCancelDelete)
    }
}

@Composable
private fun DeleteDialog(busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        // Pas de décor derrière une fenêtre de dialogue : verre sans flou.
        GlassSurface(modifier = Modifier.fillMaxWidth(), hazeState = null) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HeyText(text = stringResource(R.string.delete_title), style = HeyTheme.typography.title)
                HeyText(text = stringResource(R.string.delete_body), color = HeyTheme.colors.onBackgroundMuted)
                HeyButton(
                    text = stringResource(if (busy) R.string.delete_in_progress else R.string.delete_confirm),
                    onClick = onConfirm,
                    kind = HeyButtonKind.Danger,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                HeyButton(
                    text = stringResource(R.string.delete_cancel),
                    onClick = onCancel,
                    kind = HeyButtonKind.Secondary,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
