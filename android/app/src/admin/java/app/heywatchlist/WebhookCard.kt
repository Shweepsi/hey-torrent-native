package app.heywatchlist

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import uniffi.heywatchlist_core.WebhookUrls

private sealed interface WebhookState {
    data object Loading : WebhookState

    /** `lastUsedAt` : dernier appel reçu de Plex ou Tautulli, en secondes. */
    data class Known(val configured: Boolean, val lastUsedAt: Long?) : WebhookState

    data object Creating : WebhookState

    data class Created(val urls: WebhookUrls) : WebhookState

    data class Failed(val failure: AccountFailure) : WebhookState
}

/**
 * URL secrètes à coller dans Plex (Webhooks) et Tautulli : le serveur ne les
 * montre qu'une fois. En créer de nouvelles coupe les anciennes.
 */
@Composable
fun WebhookCard(gateway: AccountGateway) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uniffi = gateway as? UniffiAccountGateway
    var state by remember { mutableStateOf<WebhookState>(WebhookState.Loading) }

    LaunchedEffect(uniffi) {
        val target = uniffi ?: return@LaunchedEffect
        state = try {
            val account = target.account()
            val status = target.call { account.webhookStatus() }
            WebhookState.Known(status.configured, status.lastUsedAt)
        } catch (error: AccountFailureException) {
            WebhookState.Failed(error.failure)
        }
    }

    fun copy(label: String, value: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeyText(text = stringResource(R.string.admin_webhook_title), style = HeyTheme.typography.title)
            HeyText(text = stringResource(R.string.admin_webhook_body), color = HeyTheme.colors.onBackgroundMuted)
            when (val current = state) {
                is WebhookState.Known -> {
                    val text = when {
                        !current.configured -> stringResource(R.string.admin_webhook_none)
                        current.lastUsedAt == null -> stringResource(R.string.admin_webhook_never_used)
                        else -> stringResource(
                            R.string.admin_webhook_last_used,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(current.lastUsedAt * 1000)),
                        )
                    }
                    HeyText(text = text, color = HeyTheme.colors.onBackgroundMuted)
                }

                is WebhookState.Created -> {
                    HeyText(text = stringResource(R.string.admin_webhook_once), color = HeyTheme.colors.onBackgroundMuted)
                    val plexLabel = stringResource(R.string.admin_webhook_plex)
                    val tautulliLabel = stringResource(R.string.admin_webhook_tautulli)
                    val bodyLabel = stringResource(R.string.admin_webhook_tautulli_body)
                    HeyText(text = plexLabel, style = HeyTheme.typography.title)
                    SelectionContainer { HeyText(text = current.urls.plexUrl) }
                    HeyButton(
                        text = stringResource(R.string.admin_webhook_copy, plexLabel),
                        kind = HeyButtonKind.Secondary,
                        onClick = { copy(plexLabel, current.urls.plexUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HeyText(text = tautulliLabel, style = HeyTheme.typography.title)
                    SelectionContainer { HeyText(text = current.urls.tautulliUrl) }
                    HeyButton(
                        text = stringResource(R.string.admin_webhook_copy, tautulliLabel),
                        kind = HeyButtonKind.Secondary,
                        onClick = { copy(tautulliLabel, current.urls.tautulliUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HeyButton(
                        text = stringResource(R.string.admin_webhook_copy, bodyLabel),
                        kind = HeyButtonKind.Secondary,
                        onClick = { copy(bodyLabel, current.urls.tautulliBody) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is WebhookState.Failed -> HeyText(
                    text = stringResource(current.failure.messageRes),
                    color = HeyTheme.colors.error,
                )

                WebhookState.Loading, WebhookState.Creating -> Unit
            }
            val configured = (state as? WebhookState.Known)?.configured == true || state is WebhookState.Created
            HeyButton(
                text = stringResource(
                    when {
                        state == WebhookState.Creating -> R.string.admin_webhook_creating
                        configured -> R.string.admin_webhook_regenerate
                        else -> R.string.admin_webhook_create
                    },
                ),
                kind = if (configured) HeyButtonKind.Secondary else HeyButtonKind.Primary,
                enabled = uniffi != null && state != WebhookState.Creating && state != WebhookState.Loading,
                onClick = {
                    val target = uniffi ?: return@HeyButton
                    state = WebhookState.Creating
                    scope.launch {
                        state = try {
                            val account = target.account()
                            WebhookState.Created(target.call { account.regenerateWebhook() })
                        } catch (error: AccountFailureException) {
                            WebhookState.Failed(error.failure)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
