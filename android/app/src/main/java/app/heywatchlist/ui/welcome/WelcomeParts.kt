package app.heywatchlist.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.components.CinemaBackdrop
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Mise en page commune de l'accueil et de l'attente : le mur d'affiches
 * ([posters], ou dessiné) en fond, le contenu posé en bas de l'écran (à
 * portée du pouce), et qui défile si l'écran est petit ou le texte agrandi.
 */
@Composable
internal fun WelcomeLayout(posters: List<String>, content: @Composable ColumnScope.() -> Unit) {
    CinemaBackdrop(posters = posters) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
                content = content,
            )
        }
    }
}

/** Marque de l'app : un carré arrondi en dégradé, avec le symbole lecture. */
@Composable
internal fun LogoMark(size: Dp = 64.dp) {
    val colors = HeyTheme.colors
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.primary, colors.accent)))
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        HeyIcon(icon = HeyIcons.Play, contentDescription = null, tint = Color.White, iconSize = size * 0.45f)
    }
}

/** Une promesse de l'app : icône dans une pastille teintée, et une phrase. */
@Composable
internal fun FeatureRow(icon: ImageVector, text: String) {
    val colors = HeyTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            HeyIcon(icon = icon, contentDescription = null, tint = colors.primary, iconSize = 20.dp)
        }
        HeyText(text = text, modifier = Modifier.weight(1f))
    }
}

/** État d'une étape de l'attente. */
internal enum class StepState { Done, Current, Next }

/** Une étape du parcours d'accès, avec son état lu par les lecteurs d'écran (texte, pas seulement couleur). */
@Composable
internal fun StepRow(text: String, state: StepState, stateText: String) {
    val colors = HeyTheme.colors
    val (tint, icon) = when (state) {
        StepState.Done -> colors.success to HeyIcons.Check
        StepState.Current -> colors.primary to HeyIcons.Clock
        StepState.Next -> colors.onBackgroundMuted to null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f))
                .border(1.dp, tint.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) HeyIcon(icon = icon, contentDescription = null, tint = tint, iconSize = 16.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            HeyText(
                text = text,
                style = HeyTheme.typography.label,
                color = if (state == StepState.Next) colors.onBackgroundMuted else colors.onBackground,
            )
            HeyText(text = stateText, style = HeyTheme.typography.caption, color = tint)
        }
    }
}
