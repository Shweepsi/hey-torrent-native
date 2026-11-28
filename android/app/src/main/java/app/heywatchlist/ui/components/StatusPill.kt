package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/** Teinte d'une [StatusPill]. */
enum class StatusTone { Neutral, Info, Progress, Success, Danger }

/** Couleur d'une teinte dans le thème courant. */
@Composable
fun StatusTone.color(): Color {
    val colors = HeyTheme.colors
    return when (this) {
        StatusTone.Neutral -> colors.onBackgroundMuted
        StatusTone.Info -> colors.primary
        StatusTone.Progress -> colors.warning
        StatusTone.Success -> colors.success
        StatusTone.Danger -> colors.error
    }
}

/**
 * Pastille d'état (une demande, un compte) : icône et texte en plus de la
 * teinte, qui ne porte jamais seule l'information. Sans flou : elle se répète
 * dans des listes.
 */
@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier, icon: ImageVector? = null, maxLines: Int = 1) {
    val color = tone.color()
    val shape = HeyTheme.shapes.pill
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) HeyIcon(icon = icon, contentDescription = null, tint = color, iconSize = 14.dp)
        HeyText(text = text, style = HeyTheme.typography.caption, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
