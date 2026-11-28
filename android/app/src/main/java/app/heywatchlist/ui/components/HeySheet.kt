package app.heywatchlist.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Feuille qui monte du bas de l'écran, à portée du pouce : confirmation ou
 * petite saisie. Toucher le voile ou faire « retour » la ferme.
 *
 * C'est une fenêtre à part : rien derrière elle n'est flouté (Haze ne voit pas
 * l'écran du dessous), d'où la teinte de verre opaque, posée sur le voile
 * sombre qu'Android met derrière toute fenêtre de dialogue.
 */
@Composable
fun HeySheet(
    title: String,
    onDismiss: () -> Unit,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HeyTheme.colors
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val animations = rememberAnimationsEnabled()
    val shown = remember { MutableTransitionState(!animations).apply { targetState = true } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visibleState = shown,
                enter = slideInVertically { it } + fadeIn(),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.glassFillFallback)
                        .border(1.dp, glassEdge(colors), shape)
                        // Un toucher sur la feuille ne doit pas la fermer.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(colors.onBackgroundMuted.copy(alpha = 0.4f)),
                    )
                    HeyText(text = title, style = HeyTheme.typography.title, modifier = Modifier.semantics { heading() })
                    body?.let { HeyText(text = it, color = colors.onBackgroundMuted) }
                    content()
                }
            }
        }
    }
}
