package app.heywatchlist.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.heywatchlist.ui.theme.HeyTheme

/** Texte aux couleurs et à la typographie du thème (pas de composant Material). */
@Composable
fun HeyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HeyTheme.typography.body,
    color: Color = HeyTheme.colors.onBackground,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign ?: style.textAlign),
        overflow = overflow,
        maxLines = maxLines,
    )
}
