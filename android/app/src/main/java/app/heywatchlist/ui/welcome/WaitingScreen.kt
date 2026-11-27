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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.heywatchlist.EditionContent
import app.heywatchlist.R
import app.heywatchlist.core.Access
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.settings.DeleteDialog
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Compte connecté mais pas encore accepté (ou refusé) : rien d'autre de l'app
 * n'est accessible. L'accès est relu à chaque retour dans l'app et quand une
 * notification annonce l'acceptation.
 */
@Composable
fun WaitingScreen(state: AccountUiState.SignedIn, viewModel: AccountViewModel) {
    LifecycleResumeEffect(Unit) {
        viewModel.checkAccess()
        onPauseOrDispose { }
    }
    WaitingContent(
        state = state,
        onCheck = viewModel::checkAccess,
        onSignOut = viewModel::signOut,
        onDelete = viewModel::requestDeletion,
        onConfirmDelete = viewModel::confirmDeletion,
        onCancelDelete = viewModel::cancelDeletion,
        extras = { EditionContent.WaitingExtras(gateway = viewModel.gateway, onProfileChanged = viewModel::profileChanged) },
    )
}

@Composable
internal fun WaitingContent(
    state: AccountUiState.SignedIn,
    onCheck: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    extras: @Composable () -> Unit = {},
) {
    val refused = state.profile.access == Access.Refused
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
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val name = state.profile.firstName
                HeyText(
                    text = when {
                        refused -> stringResource(R.string.waiting_refused_title)
                        name != null -> stringResource(R.string.waiting_title_named, name)
                        else -> stringResource(R.string.waiting_title)
                    },
                    style = HeyTheme.typography.title,
                )
                HeyText(
                    text = stringResource(if (refused) R.string.waiting_refused_body else R.string.waiting_body),
                    color = HeyTheme.colors.onBackgroundMuted,
                )
                if (!refused) {
                    HeyButton(
                        text = stringResource(if (state.checking) R.string.waiting_checking else R.string.waiting_check),
                        onClick = onCheck,
                        enabled = !state.checking && !state.busy,
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
        extras()
        HeyButton(
            text = stringResource(R.string.settings_sign_out),
            onClick = onSignOut,
            kind = HeyButtonKind.Secondary,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        HeyButton(
            text = stringResource(if (refused) R.string.settings_delete else R.string.waiting_withdraw),
            onClick = onDelete,
            kind = HeyButtonKind.Danger,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (state.confirmingDeletion) {
        DeleteDialog(busy = state.busy, onConfirm = onConfirmDelete, onCancel = onCancelDelete)
    }
}
