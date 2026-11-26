package app.heywatchlist.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

private const val SWEEP_DEGREES = 270f

/**
 * Indicateur de chargement : un arc qui tourne. [description] est lu par les
 * lecteurs d'écran. Fixe quand les animations sont supprimées.
 */
@Composable
fun HeySpinner(
    description: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 24.dp,
    color: Color = HeyTheme.colors.primary,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val rotation: State<Float> = if (animationsEnabled) {
        rememberInfiniteTransition(label = "spinner").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, easing = LinearEasing)),
            label = "spinner-rotation",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Canvas(modifier = modifier.size(diameter).semantics { contentDescription = description }) {
        rotate(degrees = rotation.value) { drawSpinnerArc(color, SWEEP_DEGREES) }
    }
}

/** Arc qui se referme à mesure qu'on tire pour rafraîchir ([progress] de 0 à 1). */
@Composable
fun HeyProgressArc(progress: Float, modifier: Modifier = Modifier, diameter: Dp = 24.dp) {
    val color = HeyTheme.colors.primary
    Canvas(modifier = modifier.size(diameter)) {
        drawSpinnerArc(color, SWEEP_DEGREES * progress.coerceIn(0f, 1f))
    }
}

private fun DrawScope.drawSpinnerArc(color: Color, sweepAngle: Float) {
    val strokeWidth = 3.dp.toPx()
    val inset = strokeWidth / 2
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}
