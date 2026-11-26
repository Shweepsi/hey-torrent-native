package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Pastille touchable (un genre, par exemple), cible d'au moins 48 dp. Teinte
 * translucide sans flou : elle se répète dans des listes qui défilent.
 */
@Composable
fun HeyChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        HeyText(text = text, style = HeyTheme.typography.label)
    }
}

/** Pastille d'information, sans action. */
@Composable
fun HeyTag(text: String, modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        HeyText(text = text, style = HeyTheme.typography.caption)
    }
}
