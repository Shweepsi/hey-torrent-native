package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/** Apparence d'un [HeyButton]. */
enum class HeyButtonKind {
    /** Action principale : fond plein. */
    Primary,

    /** Action secondaire : verre clair et filet. */
    Secondary,

    /** Action destructrice (suppression) : texte rouge sur verre. */
    Danger,
}

/**
 * Bouton, cible tactile d'au moins 48 dp ; estompé et inactif si `enabled` est
 * faux. [icon] s'affiche avant le texte.
 */
@Composable
fun HeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: HeyButtonKind = HeyButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.button
    val (fill, content) = when (kind) {
        HeyButtonKind.Primary -> colors.primary to colors.onPrimary
        HeyButtonKind.Secondary -> colors.glassFill to colors.onBackground
        HeyButtonKind.Danger -> colors.glassFill to colors.error
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (kind == HeyButtonKind.Primary) Color.Transparent else colors.glassBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) HeyIcon(icon = icon, contentDescription = null, tint = content, iconSize = 20.dp)
            HeyText(text = text, style = HeyTheme.typography.label, color = content)
        }
    }
}
