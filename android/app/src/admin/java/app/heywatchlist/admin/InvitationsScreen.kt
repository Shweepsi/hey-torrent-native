package app.heywatchlist.admin

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyPullToRefresh
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme
import uniffi.heywatchlist_core.InvitationInfo

/**
 * Codes d'invitation : création d'un code à transmettre à un proche (valable
 * 14 jours, une seule fois), puis suivi des derniers codes et révocation.
 */
@Composable
fun InvitationsScreen(viewModel: InvitationsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowSeconds = System.currentTimeMillis() / 1000
    Column(modifier = Modifier.fillMaxSize()) {
        AdminHeader(title = stringResource(R.string.admin_tab_invitations))
        HeyPullToRefresh(
            refreshing = state.loading && state.invitations.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { CreateCard(created = state.created, creating = state.creating, onCreate = viewModel::create) }
                state.actionFailure?.let { failure -> item { AdminCard { FailureText(failure) } } }
                val failure = state.failure
                when {
                    failure != null -> item { FailurePanel(failure, onRetry = viewModel::refresh) }
                    state.loading && state.invitations.isEmpty() -> item { LoadingRow(stringResource(R.string.status_loading)) }
                    state.invitations.isEmpty() -> item { EmptyText(stringResource(R.string.admin_invitations_none)) }
                }
                items(state.invitations, key = { it.id }) { invitation ->
                    InvitationRow(
                        invitation = invitation,
                        usable = invitation.isUsable(nowSeconds),
                        nowSeconds = nowSeconds,
                        revoking = invitation.id in state.revoking,
                        onRevoke = { viewModel.revoke(invitation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateCard(created: CreatedInvitation?, creating: Boolean, onCreate: () -> Unit) {
    val context = LocalContext.current
    AdminCard {
        HeyText(text = stringResource(R.string.admin_invite_title), style = HeyTheme.typography.title)
        HeyText(text = stringResource(R.string.admin_invite_body), color = HeyTheme.colors.onBackgroundMuted)
        if (created != null) {
            SelectionContainer {
                HeyText(text = created.code, style = HeyTheme.typography.display)
            }
            HeyText(
                text = stringResource(R.string.admin_invite_expires, formatDate(created.expiresAtSeconds)),
                color = HeyTheme.colors.onBackgroundMuted,
            )
            val message = stringResource(R.string.admin_invite_share_text, created.code)
            val chooserTitle = stringResource(R.string.admin_invite_share)
            HeyButton(
                text = chooserTitle,
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                    context.startActivity(Intent.createChooser(send, chooserTitle))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HeyButton(
            text = stringResource(if (creating) R.string.admin_invite_creating else R.string.admin_invite_create),
            kind = HeyButtonKind.Secondary,
            enabled = !creating,
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun InvitationRow(
    invitation: InvitationInfo,
    usable: Boolean,
    nowSeconds: Long,
    revoking: Boolean,
    onRevoke: () -> Unit,
) {
    AdminCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeyText(text = invitationStatus(invitation, nowSeconds))
                HeyText(
                    text = stringResource(R.string.admin_invitation_created, formatDate(invitation.createdAt)),
                    style = HeyTheme.typography.caption,
                    color = HeyTheme.colors.onBackgroundMuted,
                )
            }
            if (usable) {
                HeyButton(
                    text = stringResource(R.string.admin_invitation_revoke),
                    onClick = onRevoke,
                    kind = HeyButtonKind.Danger,
                    enabled = !revoking,
                )
            }
        }
    }
}

/** Le code n'est jamais relu : seul son état s'affiche. */
@Composable
private fun invitationStatus(invitation: InvitationInfo, nowSeconds: Long): String {
    val usedAt = invitation.usedAt
    val revokedAt = invitation.revokedAt
    return when {
        usedAt != null -> invitation.usedByName?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.admin_invitation_used_by, it, formatDate(usedAt)) }
            ?: stringResource(R.string.admin_invitation_used, formatDate(usedAt))
        revokedAt != null -> stringResource(R.string.admin_invitation_revoked, formatDate(revokedAt))
        invitation.expiresAt <= nowSeconds -> stringResource(R.string.admin_invitation_expired, formatDate(invitation.expiresAt))
        else -> stringResource(R.string.admin_invitation_pending, formatDate(invitation.expiresAt))
    }
}
