package app.heywatchlist.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Faux quand la personne a supprimé les animations dans les réglages
 * d'accessibilité d'Android (échelle de durée des animations à zéro) : les
 * composants animés se figent alors. Lu à l'affichage de l'écran.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/** Enfoncement d'un élément touchable : il rétrécit un peu tant qu'on appuie. */
@Stable
class Press internal constructor(val interaction: MutableInteractionSource, internal val scale: State<Float>)

/** À partager entre [pressScale] et le `clickable` de l'élément (son `interactionSource`). */
@Composable
fun rememberPress(): Press {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val animations = rememberAnimationsEnabled()
    val scale = animateFloatAsState(
        targetValue = if (pressed && animations) PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return remember(interaction, scale) { Press(interaction, scale) }
}

/** Applique l'enfoncement de [press] ; à placer en tête de la chaîne, avant le fond. */
fun Modifier.pressScale(press: Press): Modifier = graphicsLayer {
    scaleX = press.scale.value
    scaleY = press.scale.value
}

private const val PRESSED_SCALE = 0.97f

/**
 * Arrivée d'un écran : il glisse un peu depuis la droite s'il vient d'être
 * ouvert ([forward]), sinon il réapparaît par un léger zoom ; un fondu dans les
 * deux cas. Le sens est celui de la première composition. Sans animations
 * système, l'écran est là tout de suite.
 */
@Composable
fun EnterMotion(forward: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animations = rememberAnimationsEnabled()
    val fromRight = remember { forward }
    val progress = remember { Animatable(if (animations) 0f else 1f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        modifier = modifier.fillMaxSize().graphicsLayer {
            val value = progress.value
            alpha = value
            if (fromRight) {
                translationX = size.width * ENTER_SHIFT * (1f - value)
            } else {
                val scale = REVEAL_SCALE + (1f - REVEAL_SCALE) * value
                scaleX = scale
                scaleY = scale
            }
        },
    ) {
        content()
    }
}

private const val ENTER_SHIFT = 0.08f
private const val REVEAL_SCALE = 0.97f
