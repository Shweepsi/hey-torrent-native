package app.heywatchlist.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/** Rapport du dernier plantage, par-dessus l'app : le copier, puis le fermer. */
@Composable
fun CrashReportPanel(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = HeyTheme.colors
    val copied = stringResource(R.string.crash_copied)
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.6f)).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeyText(text = stringResource(R.string.crash_title), style = HeyTheme.typography.title)
                HeyText(text = stringResource(R.string.crash_body), color = colors.onBackgroundMuted)
                HeyText(
                    text = report,
                    style = HeyTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onBackgroundMuted,
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                )
                HeyButton(
                    text = stringResource(R.string.crash_copy),
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("HeyWatchlist", report))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HeyButton(
                    text = stringResource(R.string.crash_close),
                    onClick = onDismiss,
                    kind = HeyButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
