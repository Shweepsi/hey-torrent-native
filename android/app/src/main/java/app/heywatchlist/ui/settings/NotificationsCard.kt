package app.heywatchlist.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.push.PushSetup
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Notifications « Disponible sur Plex » dans Réglages : état de la permission
 * et chemin vers les réglages du téléphone quand elles sont coupées.
 */
@Composable
fun NotificationsCard() {
    val context = LocalContext.current
    val allowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeyText(text = stringResource(R.string.settings_push_title), style = HeyTheme.typography.title)
            @StringRes val bodyRes = when {
                !PushSetup.isConfigured -> R.string.settings_push_unavailable
                !allowed -> R.string.settings_push_denied
                else -> R.string.settings_push_body
            }
            HeyText(text = stringResource(bodyRes), color = HeyTheme.colors.onBackgroundMuted)
            if (PushSetup.isConfigured && !allowed) {
                HeyButton(
                    text = stringResource(R.string.settings_push_open_settings),
                    kind = HeyButtonKind.Secondary,
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
