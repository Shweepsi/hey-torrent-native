package app.heywatchlist.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heywatchlist.ui.theme.HeyTheme

/** Un onglet d'une [GlassTabBar]. */
class GlassTab(val label: String, val icon: ImageVector)

/**
 * Barre d'onglets flottante en verre : icône et libellé, et une pastille qui
 * glisse vers l'onglet choisi (ressort). Chaque onglet fait au moins 56 dp de
 * haut. Sans animations système, la pastille saute directement.
 */
@Composable
fun GlassTabBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HeyTheme.colors
    val animations = rememberAnimationsEnabled()
    GlassSurface(modifier = modifier, shape = HeyTheme.shapes.pill) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(TabHeight + 12.dp).padding(6.dp)) {
            val tabWidth = maxWidth / tabs.size.coerceAtLeast(1)
            val indicatorX by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = if (animations) {
                    spring<Dp>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                } else {
                    snap<Dp>()
                },
                label = "tab-indicator",
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(HeyTheme.shapes.pill)
                    .background(colors.primary.copy(alpha = 0.16f))
                    .border(1.dp, colors.primary.copy(alpha = 0.32f), HeyTheme.shapes.pill),
            )
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) colors.primary else colors.onBackgroundMuted,
                        animationSpec = tween(durationMillis = if (animations) 180 else 0),
                        label = "tab-tint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(HeyTheme.shapes.pill)
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .semantics { this.selected = isSelected },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        HeyIcon(icon = tab.icon, contentDescription = null, tint = tint, iconSize = 22.dp)
                        HeyText(
                            text = tab.label,
                            style = HeyTheme.typography.caption,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val TabHeight = 56.dp
