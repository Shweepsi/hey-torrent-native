package app.heywatchlist.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
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
