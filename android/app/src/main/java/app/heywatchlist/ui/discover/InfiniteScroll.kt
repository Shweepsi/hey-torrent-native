package app.heywatchlist.ui.discover

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter

/** Nombre d'éléments restant à voir en dessous duquel on lit la page suivante. */
private const val PREFETCH_DISTANCE = 4

/**
 * Appelle [onNearEnd] quand le défilement approche de la fin de la liste. [key]
 * (le nombre de titres, par exemple) relance la surveillance après chaque page :
 * si la fin est encore proche, la page suivante est lue sans attendre un geste.
 */
@Composable
internal fun NearEndEffect(listState: LazyListState, key: Any?, onNearEnd: () -> Unit) {
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(listState, key) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - PREFETCH_DISTANCE
        }.filter { it }.collect { currentOnNearEnd() }
    }
}

/** Comme [NearEndEffect], pour une grille. */
@Composable
internal fun NearEndEffect(gridState: LazyGridState, key: Any?, onNearEnd: () -> Unit) {
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(gridState, key) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - PREFETCH_DISTANCE
        }.filter { it }.collect { currentOnNearEnd() }
    }
}
