package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.ui.theme.HeyTheme

private val TriggerDistance = 72.dp
private const val DRAG_RESISTANCE = 0.5f

/**
 * « Tirer pour rafraîchir » sans Material : quand le contenu est tout en haut et
 * qu'on tire encore vers le bas, un indicateur apparaît ; relâché au-delà du
 * seuil, il appelle [onRefresh]. [refreshing] garde l'indicateur affiché tant
 * que la lecture dure.
 */
@Composable
fun HeyPullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val triggerPx = with(LocalDensity.current) { TriggerDistance.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val currentRefreshing by rememberUpdatedState(refreshing)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val connection = remember(triggerPx) {
        object : NestedScrollConnection {
            // Quand on remonte l'indicateur, le geste lui sert d'abord, avant le contenu.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || pull <= 0f || available.y >= 0f) return Offset.Zero
                val consumed = maxOf(available.y, -pull)
                pull += consumed
                return Offset(0f, consumed)
            }

            // Ce que le contenu n'a pas pu défiler : il est en haut, on tire l'indicateur.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || currentRefreshing) return Offset.Zero
                pull = (pull + available.y * DRAG_RESISTANCE).coerceAtMost(triggerPx * 1.5f)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull >= triggerPx && !currentRefreshing) currentOnRefresh()
                pull = 0f
                return Velocity.Zero
            }
        }
    }
    Box(modifier = modifier.nestedScroll(connection)) {
        content()
        // L'indicateur n'existe que pendant le geste ou la lecture : aucune animation au repos.
        if (refreshing || pull > 0f) {
            val colors = HeyTheme.colors
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.glassFillFallback)
                    .border(1.dp, colors.glassBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    HeySpinner(description = stringResource(R.string.status_refreshing))
                } else {
                    HeyProgressArc(progress = pull / triggerPx)
                }
            }
        }
    }
}
