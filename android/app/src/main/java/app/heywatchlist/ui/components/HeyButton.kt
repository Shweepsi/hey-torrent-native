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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/** Apparence d'un [HeyButton]. */
enum class HeyButtonKind {
    /** Action principale : verre teinté de l'accent, lumineux, avec reflet et halo. Une seule par écran. */
    Primary,

    /** Action d'appoint accentuée (bande-annonce) : verre légèrement bleuté, texte accentué, comme l'onglet choisi. */
    Tinted,

    /** Action secondaire : verre dépoli neutre et filet clair. */
    Secondary,

    /** Action destructrice (suppression) : texte rouge sur verre. */
    Danger,

    /** Action discrète, sans fond ni filet : texte bleu, comme un lien. */
    Link,

    /** Action destructrice discrète (retirer) : texte rouge, sans fond ni filet. */
    DangerLink,
}

/**
 * Bouton en pastille, de la même famille que la barre d'onglets : verre aux
 * bords entièrement arrondis, filet plus clair en haut (reflet). Cible tactile
 * d'au moins 52 dp de haut. [icon] s'affiche avant le texte.
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
    val shape = HeyTheme.shapes.pill
    val press = rememberPress()
    val pressed by press.interaction.collectIsPressedAsState()
    val prominent = kind == HeyButtonKind.Primary && enabled
    val tinted = kind == HeyButtonKind.Tinted && enabled
    val plain = kind == HeyButtonKind.Link || kind == HeyButtonKind.DangerLink
    // Principal : verre accentué, plus clair en haut ; texte blanc à 4,5:1 au moins, en clair comme en sombre.
    val fill = when {
        plain -> SolidColor(Color.Transparent)
        prominent -> Brush.verticalGradient(listOf(colors.primaryFill, colors.primaryFillEnd))
        tinted -> SolidColor(colors.primary.copy(alpha = TINT_FILL))
        else -> SolidColor(colors.glassFill)
    }
    val content = when {
        !enabled -> colors.onBackgroundMuted
        prominent -> colors.onPrimary
        tinted || kind == HeyButtonKind.Link -> colors.primary
        kind == HeyButtonKind.Secondary -> colors.onBackground
        else -> colors.error
    }
    val edge = when {
        plain -> SolidColor(Color.Transparent)
        prominent -> ProminentEdge
        tinted -> Brush.verticalGradient(listOf(colors.glassHighlight, colors.primary.copy(alpha = TINT_EDGE)))
        else -> glassEdge(colors)
    }
    val pressTint = if (prominent) Color.Black.copy(alpha = 0.14f) else colors.onBackground.copy(alpha = 0.08f)
    // Halo coloré sous le bouton principal, ombre très douce sous le verre : il flotte, il ne colle pas au fond.
    val glow = when {
        plain -> null
        prominent -> colors.primaryFill.copy(alpha = 0.45f)
        else -> Color.Black.copy(alpha = 0.18f)
    }
    Box(
        modifier = modifier
            .heightIn(min = ButtonHeight)
            .pressScale(press)
            .then(
                if (glow == null) {
                    Modifier
                } else {
                    Modifier.shadow(if (prominent) 14.dp else 6.dp, shape, clip = false, ambientColor = glow, spotColor = glow)
                },
            )
            .clip(shape)
            .background(fill)
            .drawWithContent {
                drawContent()
                // Reflet du verre : la moitié haute s'éclaircit, comme une surface bombée.
                if (prominent) drawRect(ProminentSheen, size = size.copy(height = size.height * 0.55f))
                if (pressed && enabled) drawRect(pressTint)
            }
            .border(1.dp, edge, shape)
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) HeyIcon(icon = icon, contentDescription = null, tint = content, iconSize = 20.dp)
            HeyText(text = text, style = HeyTheme.typography.label, color = content)
        }
    }
}

private val ButtonHeight = 52.dp
private const val TINT_FILL = 0.14f
private const val TINT_EDGE = 0.30f

/** Filet du bouton principal : clair en haut, presque invisible en bas. */
private val ProminentEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f)))

/** Reflet de la moitié haute du bouton principal. */
private val ProminentSheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0f)))
