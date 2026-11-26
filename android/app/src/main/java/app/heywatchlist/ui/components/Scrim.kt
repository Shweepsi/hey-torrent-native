package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Texte posé sur une image : toujours clair, car le dégradé en dessous est sombre dans les deux thèmes. */
val OnScrim = Color(0xFFF5F5F8)

private val ScrimColor = Color(0xFF0C0C0F)

/**
 * Dégradé sombre, transparent en haut et presque opaque en bas, à poser sous un
 * texte qui recouvre une image : on ne met jamais de texte directement sur une
 * affiche (`docs/DESIGN.md`).
 */
@Composable
fun Scrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.45f to ScrimColor.copy(alpha = 0.55f),
                1f to ScrimColor.copy(alpha = 0.92f),
            ),
        ),
    )
}
