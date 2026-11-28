package app.heywatchlist.ui.watchlist

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Title
import app.heywatchlist.core.WatchlistEntry
import app.heywatchlist.ui.components.FloatingTopBar
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyPoster
import app.heywatchlist.ui.components.HeyPullToRefresh
import app.heywatchlist.ui.components.HeySpinner
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.components.POSTER_ASPECT_RATIO
import app.heywatchlist.ui.components.StaleBanner
import app.heywatchlist.ui.components.barsPadding
import app.heywatchlist.ui.components.glassEdge
import app.heywatchlist.ui.components.pressScale
import app.heywatchlist.ui.components.rememberPress
import app.heywatchlist.ui.components.scrolledFraction
import app.heywatchlist.ui.components.statusBarHeight
import app.heywatchlist.ui.components.underBars
import app.heywatchlist.ui.discover.pluralString
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
    val listState = rememberLazyListState()
    // Bord à bord : la liste passe sous la barre du haut et sous la barre d'onglets, qui la floutent.
    Box(modifier = Modifier.fillMaxSize()) {
        WatchlistList(state, listState, onRefresh, onOpenTitle)
        FloatingTopBar(title = stringResource(R.string.tab_watchlist), revealed = { listState.scrolledFraction() })
    }
}

@Composable
private fun WatchlistList(state: WatchlistUiState, listState: LazyListState, onRefresh: () -> Unit, onOpenTitle: (Title) -> Unit) {
    HeyPullToRefresh(
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        indicatorTop = statusBarHeight() + 8.dp,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().underBars(LocalHazeState.current),
            contentPadding = barsPadding(horizontal = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Column(modifier = Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeyText(
                        text = stringResource(R.string.tab_watchlist),
                        style = HeyTheme.typography.display,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (state.entries.isNotEmpty()) {
                        HeyText(
                            text = pluralString(R.plurals.watchlist_count, state.entries.size, state.entries.size),
                            color = HeyTheme.colors.onBackgroundMuted,
                        )
                    }
                }
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
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HeyIcon(icon = HeyIcons.Bookmark, contentDescription = null, tint = HeyTheme.colors.primary, iconSize = 36.dp)
                            HeyText(
                                text = stringResource(R.string.watchlist_empty),
                                color = HeyTheme.colors.onBackgroundMuted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                else -> items(state.entries, key = { it.key }) { entry ->
                    EntryRow(entry = entry, onClick = { onOpenTitle(entry.toTitle()) })
                }
            }
        }
    }
}

/**
 * Une ligne : vignette, titre, année et type, saisons, état de la demande.
 * Teinte translucide sans flou : la ligne se répète dans une liste qui défile.
 */
@Composable
private fun EntryRow(entry: WatchlistEntry, onClick: () -> Unit) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.card
    val press = rememberPress()
    val kind = stringResource(if (entry.kind == MediaKind.Movie) R.string.kind_movie else R.string.kind_series)
    val meta = listOfNotNull(kind, entry.year?.toString(), seasonsLabel(entry)).joinToString(META_SEPARATOR)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(press)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, glassEdge(colors), shape)
            .clickable(interactionSource = press.interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeyPoster(
            url = entry.posterUrl,
            modifier = Modifier.width(ThumbWidth).aspectRatio(POSTER_ASPECT_RATIO),
            shape = RoundedCornerShape(14.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            StatePill(entry)
        }
    }
}
