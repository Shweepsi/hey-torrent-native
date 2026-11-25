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
 * d'une teinte translucide et d'un filet clair de 1 dp.
 *
 * Sans [hazeState], ou quand Haze coupe le flou (certains appareils sous
 * Android 12), la surface devient translucide et plus opaque, sans flou : le
 * texte reste lisible et rien ne plante.
 *
 * Le flou coûte cher : à réserver aux surfaces fixes, jamais à chaque élément
 * d'une liste qui défile.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = HeyTheme.shapes.card,
    hazeState: HazeState? = LocalHazeState.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = HeyTheme.colors
    val glass = if (hazeState != null) {
        val style = remember(colors) { colors.glassStyle() }
        Modifier.hazeEffect(state = hazeState, style = style)
    } else {
        Modifier.background(colors.glassFillFallback)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(glass)
            .border(width = 1.dp, color = colors.glassBorder, shape = shape),
        content = content,
    )
}

private fun HeyColors.glassStyle() = HazeStyle(
    backgroundColor = background,
    tints = listOf(HazeTint(glassFill)),
    blurRadius = 24.dp,
    noiseFactor = 0.04f,
    fallbackTint = HazeTint(glassFillFallback),
)
