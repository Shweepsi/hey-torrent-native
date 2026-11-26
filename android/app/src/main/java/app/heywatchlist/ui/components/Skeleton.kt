package app.heywatchlist.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import app.heywatchlist.ui.theme.HeyTheme

private const val REST_ALPHA = 0.1f

/**
 * Opacité des squelettes, partagée : un seul mouvement par écran, quel que soit
 * le nombre de squelettes affichés. Sans fournisseur, les squelettes sont fixes.
 */
val LocalSkeletonPulse = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(REST_ALPHA) }

/**
 * Le mouvement des squelettes d'un écran. L'animation ne tourne que si [active]
 * (au moins un squelette est affiché) et si les animations ne sont pas
 * supprimées dans l'accessibilité : sinon la valeur reste fixe.
 */
@Composable
fun rememberSkeletonPulse(active: Boolean): State<Float> {
    val animationsEnabled = rememberAnimationsEnabled()
    return if (active && animationsEnabled) {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.07f,
            targetValue = 0.16f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeleton-pulse",
        )
    } else {
        remember { mutableFloatStateOf(REST_ALPHA) }
    }
}

/** Emplacement en attente de contenu : une forme qui respire doucement. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = HeyTheme.shapes.poster) {
    val pulse = LocalSkeletonPulse.current
    val color = HeyTheme.colors.onBackground
    // La valeur n'est lue qu'au dessin : le mouvement ne recompose rien.
    Box(modifier = modifier.clip(shape).drawBehind { drawRect(color = color, alpha = pulse.value) })
}
