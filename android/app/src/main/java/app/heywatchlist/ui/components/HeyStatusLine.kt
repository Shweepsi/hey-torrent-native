package app.heywatchlist.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/** Sens d'un [HeyStatusLine]. */
enum class StatusKind { Success, Failure }

/**
 * Résultat d'une action (test de connexion, enregistrement) : icône et texte
 * de la même teinte franche, vert ou rouge. L'icône double la couleur, qui ne
 * porte jamais seule l'information ; le texte est annoncé par les lecteurs
 * d'écran dès qu'il change.
 */
@Composable
fun HeyStatusLine(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    val (tint, icon) = when (kind) {
        StatusKind.Success -> colors.success to HeyIcons.Check
        StatusKind.Failure -> colors.error to HeyIcons.Alert
    }
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeyIcon(icon = icon, contentDescription = null, tint = tint, iconSize = 18.dp, modifier = Modifier.padding(top = 1.dp))
        HeyText(text = text, style = HeyTheme.typography.label, color = tint)
    }
}
