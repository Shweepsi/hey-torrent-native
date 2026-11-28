package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyColors
import app.heywatchlist.ui.theme.HeyTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Fond à flouter, fourni par l'écran (le `hazeSource` qui porte son décor).
 * Absent, [GlassSurface] se replie sur une surface translucide sans flou.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Surface en verre dépoli : le décor derrière elle est flouté (Haze), recouvert
 * d'une teinte translucide et d'un filet de 1 dp qui s'éclaircit vers le haut,
 * comme un reflet sur le bord du verre.
 *
 * Sans [hazeState], ou quand Haze coupe le flou (certains appareils sous
 * Android 12), la surface devient translucide et plus opaque, sans flou : le
 * texte reste lisible et rien ne plante.
 *
 * Le flou coûte cher : à réserver aux surfaces fixes, jamais à chaque élément
 * d'une liste qui défile. [tint] : la teinte posée sur le flou ([HeyColors.chromeFill]
 * pour une barre flottante au-dessus du contenu).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = HeyTheme.shapes.card,
    hazeState: HazeState? = LocalHazeState.current,
    tint: Color = HeyTheme.colors.glassFill,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = HeyTheme.colors
    val glass = if (hazeState != null) {
        val style = remember(colors, tint) { colors.glassStyle(tint) }
        Modifier.hazeEffect(state = hazeState, style = style)
    } else {
        Modifier.background(colors.glassFillFallback)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(glass)
            .border(width = 1.dp, brush = glassEdge(colors), shape = shape),
        content = content,
    )
}

/** Filet du verre : reflet clair en haut, filet ordinaire ensuite. */
fun glassEdge(colors: HeyColors): Brush = Brush.verticalGradient(
    0f to colors.glassHighlight,
    0.35f to colors.glassBorder,
    1f to colors.glassBorder,
)

internal fun HeyColors.glassStyle(tint: Color) = HazeStyle(
    backgroundColor = background,
    tints = listOf(HazeTint(tint)),
    blurRadius = 24.dp,
    noiseFactor = 0.04f,
    fallbackTint = HazeTint(glassFillFallback),
)
