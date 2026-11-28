package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
 * Bouton, cible tactile d'au moins 48 dp. [icon] s'affiche avant le texte.
 *
 * États : enfoncé, il rétrécit un peu et s'assombrit ; inactif (`enabled`
 * faux), il perd sa couleur (verre neutre, texte estompé) au lieu de devenir un
 * bleu délavé qu'on prendrait pour un bouton actif.
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
    val pressed by press.interaction.collectIsPressedAsState()
    val filled = kind == HeyButtonKind.Primary
    val plain = kind == HeyButtonKind.Link || kind == HeyButtonKind.DangerLink
    // Principal : aplat accentué en léger dégradé ; texte blanc à 4,5:1 au moins, en clair comme en sombre.
    val fill = when {
        plain -> SolidColor(Color.Transparent)
        filled && enabled -> Brush.verticalGradient(listOf(colors.primaryFill, colors.primaryFillEnd))
        else -> SolidColor(colors.glassFill)
    }
    val content = when {
        !enabled -> colors.onBackgroundMuted
        filled -> colors.onPrimary
        kind == HeyButtonKind.Secondary -> colors.onBackground
        kind == HeyButtonKind.Link -> colors.primary
        else -> colors.error
    }
    val edge = when {
        plain -> SolidColor(Color.Transparent)
        filled && enabled -> PrimaryEdge
        else -> glassEdge(colors)
    }
    val pressTint = if (filled && enabled) Color.Black.copy(alpha = 0.14f) else colors.onBackground.copy(alpha = 0.08f)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(press)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .drawWithContent {
                drawContent()
                if (pressed && enabled) drawRect(pressTint)
            }
            .clickable(
                interactionSource = press.interaction,
                indication = null,
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

/** Reflet du bouton principal : un filet clair en haut, presque invisible en bas. */
private val PrimaryEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f)))
