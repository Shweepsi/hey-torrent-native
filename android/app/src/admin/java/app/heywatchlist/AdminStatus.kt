package app.heywatchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.heywatchlist_core.adminPing
import uniffi.heywatchlist_core.hasAdmin

private sealed interface AdminState {
    data object Loading : AdminState

    data object Error : AdminState

    data class Ready(val ping: String, val included: Boolean) : AdminState
}

/**
 * Lit l'état admin du cœur ; à appeler hors du fil principal. Fonction non
 * suspendante : attraper `Exception` n'avale donc aucune annulation. Une
 * panique du cœur arrive en `InternalException`, qui n'est pas une
 * `RuntimeException`.
 */
private fun readAdminState(): AdminState = try {
    AdminState.Ready(ping = adminPing(), included = hasAdmin())
} catch (error: Exception) {
    AdminState.Error
} catch (error: LinkageError) {
    AdminState.Error
}

/**
 * Carte de l'édition admin : réponse `adminPing()` du cœur et présence du code
 * d'administration (`hasAdmin()`). N'existe que dans l'édition admin.
 */
@Composable
fun AdminStatus(modifier: Modifier = Modifier) {
    val state by produceState<AdminState>(initialValue = AdminState.Loading) {
        value = withContext(Dispatchers.IO) { readAdminState() }
    }
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeyText(
                text = stringResource(R.string.admin_status_title),
                style = HeyTheme.typography.title,
            )
            when (val current = state) {
                AdminState.Loading -> HeyText(
                    text = stringResource(R.string.admin_status_loading),
                    color = HeyTheme.colors.onBackgroundMuted,
                )

                AdminState.Error -> HeyText(
                    text = stringResource(R.string.admin_status_error),
                    color = HeyTheme.colors.error,
                )

                is AdminState.Ready -> {
                    StatusRow(stringResource(R.string.admin_status_ping_label), current.ping)
                    StatusRow(
                        label = stringResource(R.string.admin_status_flag_label),
                        value = stringResource(
                            if (current.included) R.string.admin_status_flag_yes else R.string.admin_status_flag_no,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeyText(text = label, color = HeyTheme.colors.onBackgroundMuted)
        HeyText(text = value, style = HeyTheme.typography.label)
    }
}
