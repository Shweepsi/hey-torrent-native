package app.heywatchlist.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Halos de couleur doux sur le fond : de quoi donner du relief au verre dépoli,
 * en attendant les affiches et fonds TMDB. À marquer `hazeSource` par l'écran.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    Box(
        modifier = modifier.drawBehind {
            val longestSide = maxOf(size.width, size.height)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.18f),
                    radius = longestSide * 0.6f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accent.copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(size.width * 0.9f, size.height * 0.85f),
                    radius = longestSide * 0.7f,
                ),
            )
        },
    )
}
