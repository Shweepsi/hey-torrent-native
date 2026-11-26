package app.heywatchlist.admin

import android.content.Intent
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
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/** Carte de Réglages qui ouvre l'espace admin. */
@Composable
fun AdminEntryCard() {
    val context = LocalContext.current
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeyText(text = stringResource(R.string.admin_entry_title), style = HeyTheme.typography.title)
            HeyText(text = stringResource(R.string.admin_entry_body), color = HeyTheme.colors.onBackgroundMuted)
            HeyButton(
                text = stringResource(R.string.admin_entry_open),
                onClick = { context.startActivity(Intent(context, AdminActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
