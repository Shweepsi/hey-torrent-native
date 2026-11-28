package app.heywatchlist.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.core.Genre
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Title
import app.heywatchlist.ui.components.ErrorPanel
import app.heywatchlist.ui.components.FloatingHeaderLayout
import app.heywatchlist.ui.components.GlassBar
import app.heywatchlist.ui.components.HeyIconButton
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeySegmented
import app.heywatchlist.ui.components.HeySpinner
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.components.LocalSkeletonPulse
import app.heywatchlist.ui.components.StaleBanner
import app.heywatchlist.ui.components.TitleCard
import app.heywatchlist.ui.components.TitleCardSkeleton
import app.heywatchlist.ui.components.rememberSkeletonPulse
import app.heywatchlist.ui.components.scrolledFraction
import app.heywatchlist.ui.components.underBars
import app.heywatchlist.ui.components.underHeaderPadding
import app.heywatchlist.ui.theme.HeyTheme

private val MinCardWidth = 104.dp
private const val SKELETON_CARDS = 9

/** Les titres d'un genre, en films ou en séries, dans une grille qui se prolonge au défilement. */
@Composable
fun GenreScreen(genre: Genre, viewModel: GenreViewModel, onBack: () -> Unit, onOpenTitle: (Title) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GenreContent(
        genre = genre,
        state = state,
        onBack = onBack,
        onSelect = viewModel::select,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onOpenTitle = onOpenTitle,
    )
}

@Composable
internal fun GenreContent(
    genre: Genre,
    state: GenreUiState,
    onBack: () -> Unit,
    onSelect: (MediaKind) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenTitle: (Title) -> Unit,
) {
    val pulse = rememberSkeletonPulse(active = state.list is GenreListState.Loading)
    // Films ou séries : chaque liste repart du haut.
    val gridState = rememberSaveable(state.kind, saver = LazyGridState.Saver) { LazyGridState() }
    val haze = LocalHazeState.current
    CompositionLocalProvider(LocalSkeletonPulse provides pulse) {
        // En-tête flottant : la grille défile dessous, il la floute.
        FloatingHeaderLayout(
            header = {
                GlassBar(revealed = { if (state.list is GenreListState.Loaded) gridState.scrolledFraction() else 0f }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HeyIconButton(
                            icon = HeyIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                            onClick = onBack,
                        )
                        HeyText(
                            text = genre.name,
                            style = HeyTheme.typography.headline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                    }
                    val kinds = listOf(MediaKind.Movie, MediaKind.Tv)
                    HeySegmented(
                        options = listOf(stringResource(R.string.kind_movies), stringResource(R.string.kind_series_plural)),
                        selectedIndex = kinds.indexOf(state.kind),
                        onSelect = { onSelect(kinds[it]) },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    )
                }
            },
        ) { headerHeight ->
            val padding = underHeaderPadding(headerHeight)
            when (val list = state.list) {
                GenreListState.Loading -> SkeletonGrid(padding)
                is GenreListState.Failed -> ErrorPanel(list.failure, onRetry, Modifier.padding(padding).padding(top = 8.dp))
                is GenreListState.Loaded -> if (list.titles.isEmpty()) {
                    // Côté séries, une page vide est aussi un genre qui n'a pas d'équivalent.
                    val message = if (state.kind == MediaKind.Tv) R.string.genre_no_series else R.string.genre_no_titles
                    Message(stringResource(message), padding)
                } else {
                    TitleGrid(list, gridState, padding, onLoadMore, onOpenTitle, Modifier.underBars(haze))
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, padding: PaddingValues) {
    HeyText(
        text = text,
        color = HeyTheme.colors.onBackgroundMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 16.dp, vertical = 40.dp),
    )
}

@Composable
private fun TitleGrid(
    list: GenreListState.Loaded,
    gridState: LazyGridState,
    padding: PaddingValues,
    onLoadMore: () -> Unit,
    onOpenTitle: (Title) -> Unit,
    modifier: Modifier = Modifier,
) {
    NearEndEffect(gridState = gridState, key = list.titles.size, onNearEnd = onLoadMore)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = MinCardWidth),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (list.stale) {
            item(key = "stale", span = { GridItemSpan(maxLineSpan) }) { StaleBanner() }
        }
        items(list.titles, key = { it.key }) { title ->
            TitleCard(
                title = title,
                onClick = { onOpenTitle(title) },
                modifier = Modifier.fillMaxWidth(),
                caption = title.year?.toString(),
            )
        }
        if (list.loadingMore) {
            item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    HeySpinner(description = stringResource(R.string.status_loading))
                }
            }
        }
        list.moreFailure?.let { failure ->
            item(key = "more-failure", span = { GridItemSpan(maxLineSpan) }) {
                ErrorPanel(failure = failure, onRetry = onLoadMore)
            }
        }
    }
}

@Composable
private fun SkeletonGrid(padding: PaddingValues) {
    val loading = stringResource(R.string.status_loading)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = MinCardWidth),
        modifier = Modifier.fillMaxSize().semantics { contentDescription = loading },
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(count = SKELETON_CARDS) { TitleCardSkeleton(modifier = Modifier.fillMaxWidth()) }
    }
}
