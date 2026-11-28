package app.heywatchlist.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.heywatchlist.BuildConfig
import app.heywatchlist.EditionContent
import app.heywatchlist.R
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.account.AccountViewModel
import app.heywatchlist.ui.components.FloatingTopBar
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.components.barsPadding
import app.heywatchlist.ui.components.scrolledFraction
import app.heywatchlist.ui.components.underBars
import app.heywatchlist.ui.theme.HeyTheme
import uniffi.heywatchlist_core.privacyPolicyUrl

/** Réglages : prénom du compte, déconnexion, suppression du compte, version, mentions. */
@Composable
fun SettingsScreen(state: AccountUiState.SignedIn, viewModel: AccountViewModel) {
    // Adresse construite par le cœur ; absente si le serveur n'est pas réglé.
    val privacyUrl = remember { privacyPolicyUrl(BuildConfig.API_BASE_URL) }
    SettingsContent(
        state = state,
        privacyUrl = privacyUrl,
        onSignOut = viewModel::signOut,
        onDelete = viewModel::requestDeletion,
        onConfirmDelete = viewModel::confirmDeletion,
        onCancelDelete = viewModel::cancelDeletion,
        extras = {
            NotificationsCard()
            EditionContent.SettingsExtras(gateway = viewModel.gateway, isAdmin = state.profile.isAdmin)
        },
    )
}

@Composable
internal fun SettingsContent(
    state: AccountUiState.SignedIn,
    privacyUrl: String?,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    extras: @Composable () -> Unit = {},
) {
    val scroll = rememberScrollState()
    // Bord à bord : la page passe sous la barre du haut et sous la barre d'onglets, qui la floutent.
    Box(modifier = Modifier.fillMaxSize()) {
        SettingsPage(state, privacyUrl, scroll, onSignOut, onDelete, extras)
        FloatingTopBar(title = stringResource(R.string.tab_settings), revealed = { scroll.scrolledFraction() })
    }
    if (state.confirmingDeletion) {
        DeleteDialog(busy = state.busy, onConfirm = onConfirmDelete, onCancel = onCancelDelete)
    }
}

@Composable
private fun SettingsPage(
    state: AccountUiState.SignedIn,
    privacyUrl: String?,
    scroll: ScrollState,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    extras: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .underBars(LocalHazeState.current)
            .verticalScroll(scroll)
            .padding(barsPadding(horizontal = 24.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeyText(
            text = stringResource(R.string.tab_settings),
            style = HeyTheme.typography.display,
            modifier = Modifier.semantics { heading() },
        )
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name = state.profile.firstName
                ProfileBadge(name = name)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        privacyUrl?.let { url -> PrivacyButton(url) }
        HeyText(
            text = stringResource(R.string.settings_tmdb_notice),
            style = HeyTheme.typography.caption,
            color = HeyTheme.colors.onBackgroundMuted,
        )
    }
}

/** Pastille ronde à l'initiale du prénom (ou une silhouette), en dégradé. Décorative. */
@Composable
private fun ProfileBadge(name: String?) {
    val colors = HeyTheme.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.primary, colors.accent)))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.trim()?.take(1)?.uppercase()
        if (initial.isNullOrEmpty()) {
            HeyIcon(icon = HeyIcons.Person, contentDescription = null, tint = Color.White, iconSize = 28.dp)
        } else {
            HeyText(text = initial, style = HeyTheme.typography.headline, color = Color.White)
        }
    }
}

/** Ouvre la politique de confidentialité dans le navigateur du système. */
@Composable
private fun PrivacyButton(url: String) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.settings_privacy_unavailable)
    HeyButton(
        text = stringResource(R.string.settings_privacy),
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
            }
        },
        kind = HeyButtonKind.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun DeleteDialog(busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
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
