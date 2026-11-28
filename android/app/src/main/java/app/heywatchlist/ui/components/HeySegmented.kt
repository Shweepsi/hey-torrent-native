package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Choix exclusif entre quelques options (Films / Séries), chacune une cible
 * d'au moins 48 dp. L'option choisie est une pastille de verre plus claire,
 * pas un aplat de couleur : le bleu plein reste réservé à l'action principale.
 */
@Composable
fun HeySegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HeyTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.onBackground.copy(alpha = 0.14f) else Color.Transparent)
                    .border(1.dp, if (selected) colors.glassBorder else Color.Transparent, CircleShape)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                HeyText(
                    text = label,
                    style = HeyTheme.typography.label,
                    color = if (selected) colors.onBackground else colors.onBackgroundMuted,
                )
            }
        }
    }
}
