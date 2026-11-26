package app.heywatchlist.ui.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Title
import app.heywatchlist.core.WatchlistEntry
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyPoster
import app.heywatchlist.ui.components.HeyPullToRefresh
import app.heywatchlist.ui.components.HeySpinner
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.POSTER_ASPECT_RATIO
import app.heywatchlist.ui.components.StaleBanner
import app.heywatchlist.ui.theme.HeyTheme

private val ThumbWidth = 64.dp
private const val META_SEPARATOR = " · "

/** Onglet Watchlist : mes titres et l'état de chaque demande, la plus récente d'abord. */
@Composable
fun WatchlistScreen(viewModel: WatchlistViewModel, onOpenTitle: (Title) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WatchlistContent(state = state, onRefresh = viewModel::refresh, onOpenTitle = onOpenTitle)
}

@Composable
internal fun WatchlistContent(state: WatchlistUiState, onRefresh: () -> Unit, onOpenTitle: (Title) -> Unit) {
    HeyPullToRefresh(refreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                HeyText(
                    text = stringResource(R.string.tab_watchlist),
                    style = HeyTheme.typography.display,
                    modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
                )
            }
            if (state.offline) {
                item(key = "offline") { StaleBanner(textRes = R.string.watchlist_offline) }
            }
            when {
                !state.loaded -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        HeySpinner(description = stringResource(R.string.status_loading))
                    }
                }

                state.entries.isEmpty() -> item(key = "empty") {
                    GlassSurface(modifier = Modifier.fillMaxWidth()) {
                        HeyText(
                            text = stringResource(R.string.watchlist_empty),
                            color = HeyTheme.colors.onBackgroundMuted,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }

                else -> items(state.entries, key = { it.key }) { entry ->
                    EntryRow(entry = entry, onClick = { onOpenTitle(entry.toTitle()) })
                }
            }
        }
    }
}

/** Une ligne : vignette, titre, année et type, saisons, état de la demande. */
@Composable
private fun EntryRow(entry: WatchlistEntry, onClick: () -> Unit) {
    val colors = HeyTheme.colors
    val kind = stringResource(if (entry.kind == MediaKind.Movie) R.string.kind_movie else R.string.kind_series)
    val meta = listOfNotNull(kind, entry.year?.toString(), seasonsLabel(entry)).joinToString(META_SEPARATOR)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HeyTheme.shapes.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeyPoster(url = entry.posterUrl, modifier = Modifier.width(ThumbWidth).aspectRatio(POSTER_ASPECT_RATIO))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeyText(
                text = entry.title,
                style = HeyTheme.typography.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HeyText(
                text = meta,
                style = HeyTheme.typography.caption,
                color = colors.onBackgroundMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HeyText(text = stateLabel(entry), style = HeyTheme.typography.caption, color = stateColor(entry))
        }
    }
}
