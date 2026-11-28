package app.heywatchlist.ui.components

import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
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

    /** Action discrète, sans fond ni filet : texte bleu, comme un lien. */
    Link,

    /** Action destructrice discrète (retirer) : texte rouge, sans fond ni filet. */
    DangerLink,
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
    val press = rememberPress()
    // Principal : léger dégradé vertical vers un bleu un peu plus sombre (contraste du texte au moins 4,5:1 partout).
    val (fill, content) = when (kind) {
        HeyButtonKind.Primary -> Brush.verticalGradient(listOf(colors.primary, colors.primary.darken())) to colors.onPrimary
        HeyButtonKind.Secondary -> SolidColor(colors.glassFill) to colors.onBackground
        HeyButtonKind.Danger -> SolidColor(colors.glassFill) to colors.error
        HeyButtonKind.Link -> SolidColor(Color.Transparent) to colors.primary
        HeyButtonKind.DangerLink -> SolidColor(Color.Transparent) to colors.error
    }
    val edge = when (kind) {
        HeyButtonKind.Primary -> SolidColor(Color.White.copy(alpha = 0.18f))
        HeyButtonKind.Link, HeyButtonKind.DangerLink -> SolidColor(Color.Transparent)
        else -> glassEdge(colors)
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(press)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .clickable(
                interactionSource = press.interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) HeyIcon(icon = icon, contentDescription = null, tint = content, iconSize = 20.dp)
            HeyText(text = text, style = HeyTheme.typography.label, color = content)
        }
    }
}

/** Une teinte un peu plus sombre, pour le bas d'un dégradé. */
private fun Color.darken(): Color = lerp(this, Color.Black, 0.12f)
