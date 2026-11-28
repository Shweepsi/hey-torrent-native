package app.heywatchlist.ui.welcome

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.rememberAnimationsEnabled
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
    WelcomeLayout {
        StatusOrb(refused = refused)
        HeyText(
            text = stringResource(R.string.app_name),
            style = HeyTheme.typography.headline,
            color = HeyTheme.colors.onBackgroundMuted,
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StepRow(stringResource(R.string.waiting_step_account), StepState.Done, stringResource(R.string.waiting_step_done))
                        StepRow(stringResource(R.string.waiting_step_review), StepState.Current, stringResource(R.string.waiting_step_current))
                        StepRow(stringResource(R.string.waiting_step_access), StepState.Next, stringResource(R.string.waiting_step_next))
                    }
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

/**
 * Pastille d'état en haut de l'attente : une horloge entourée d'un halo qui
 * respire, ou une croix pour un accès refusé (sans mouvement).
 */
@Composable
private fun StatusOrb(refused: Boolean) {
    val colors = HeyTheme.colors
    val tint = if (refused) colors.error else colors.primary
    val breathing = !refused && rememberAnimationsEnabled()
    val halo = if (breathing) {
        rememberInfiniteTransition(label = "waiting-orb").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "waiting-orb-halo",
        )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }
    Box(
        modifier = Modifier.size(88.dp).drawBehind {
            // Le halo n'est lu qu'au dessin : son mouvement ne recompose rien.
            val value = halo.value
            drawCircle(color = tint.copy(alpha = 0.10f + 0.14f * value), radius = size.minDimension / 2f * (0.86f + 0.14f * value))
        },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(modifier = Modifier.size(64.dp), shape = CircleShape) {
            HeyIcon(
                icon = if (refused) HeyIcons.Close else HeyIcons.Clock,
                contentDescription = null,
                tint = tint,
                iconSize = 30.dp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
