package app.heywatchlist

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heywatchlist.core.AccountFailure
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.AccountGateway
import app.heywatchlist.core.UniffiAccountGateway
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private sealed interface InvitationState {
    data object Idle : InvitationState

    data object Creating : InvitationState

    data class Created(val code: String, val expiresAtSeconds: Long) : InvitationState

    data class Failed(val failure: AccountFailure) : InvitationState
}

/**
 * Création d'un code d'invitation à envoyer à un proche (valable 14 jours,
 * une seule fois). N'existe que dans l'édition admin.
 */
@Composable
fun InvitationCard(gateway: AccountGateway) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember { mutableStateOf<InvitationState>(InvitationState.Idle) }
    val uniffi = gateway as? UniffiAccountGateway

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeyText(text = stringResource(R.string.admin_invite_title), style = HeyTheme.typography.title)
            HeyText(text = stringResource(R.string.admin_invite_body), color = HeyTheme.colors.onBackgroundMuted)
            when (val current = state) {
                is InvitationState.Created -> {
                    SelectionContainer {
                        HeyText(text = current.code, style = HeyTheme.typography.display)
                    }
                    val expiry = DateFormat.getDateInstance(DateFormat.LONG).format(Date(current.expiresAtSeconds * 1000))
                    HeyText(
                        text = stringResource(R.string.admin_invite_expires, expiry),
                        color = HeyTheme.colors.onBackgroundMuted,
                    )
                    val message = stringResource(R.string.admin_invite_share_text, current.code)
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

                is InvitationState.Failed -> HeyText(
                    text = stringResource(current.failure.messageRes),
                    color = HeyTheme.colors.error,
                )

                InvitationState.Idle, InvitationState.Creating -> Unit
            }
            HeyButton(
                text = stringResource(
                    if (state == InvitationState.Creating) R.string.admin_invite_creating else R.string.admin_invite_create,
                ),
                kind = HeyButtonKind.Secondary,
                enabled = uniffi != null && state != InvitationState.Creating,
                onClick = {
                    val target = uniffi ?: return@HeyButton
                    state = InvitationState.Creating
                    scope.launch {
                        state = try {
                            val invitation = target.call { target.account().createInvitation() }
                            InvitationState.Created(invitation.code, invitation.expiresAt)
                        } catch (error: AccountFailureException) {
                            InvitationState.Failed(error.failure)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
