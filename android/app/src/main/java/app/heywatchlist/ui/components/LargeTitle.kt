package app.heywatchlist.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Grand titre d'un onglet, en tête du contenu qui défile, identique sur les
 * trois onglets : même marge, même taille, sous-titre estompé facultatif. Au
 * défilement, il passe sous la barre flottante, qui reprend le titre en petit.
 *
 * [refreshLabel] : sans geste de tirer avec TalkBack, « Actualiser » devient
 * une action du titre. [trailing] : élément aligné sur la première ligne.
 */
@Composable
fun LargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    refreshLabel: String? = null,
    onRefresh: () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeyText(
                text = title,
                style = HeyTheme.typography.display,
                maxLines = 1,
                modifier = Modifier.weight(1f).semantics {
                    heading()
                    if (refreshLabel != null) {
                        customActions = listOf(CustomAccessibilityAction(refreshLabel) { onRefresh(); true })
                    }
                },
            )
            trailing()
        }
        if (subtitle != null) {
            HeyText(text = subtitle, style = HeyTheme.typography.label, color = HeyTheme.colors.onBackgroundMuted)
        }
    }
}
