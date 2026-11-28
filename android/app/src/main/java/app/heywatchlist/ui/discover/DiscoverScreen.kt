package app.heywatchlist.ui.discover

import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.core.Genre
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Shelf
import app.heywatchlist.core.Title
import app.heywatchlist.ui.components.ErrorPanel
import app.heywatchlist.ui.components.HeyChip
import app.heywatchlist.ui.components.HeyPoster
import app.heywatchlist.ui.components.HeyPullToRefresh
import app.heywatchlist.ui.components.HeySearchEntry
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.LocalSkeletonPulse
import app.heywatchlist.ui.components.OnScrim
import app.heywatchlist.ui.components.Scrim
import app.heywatchlist.ui.components.Skeleton
import app.heywatchlist.ui.components.StaleBanner
import app.heywatchlist.ui.components.TitleCard
import app.heywatchlist.ui.components.TitleCardSkeleton
import app.heywatchlist.ui.components.rememberAnimationsEnabled
import app.heywatchlist.ui.components.rememberSkeletonPulse
import app.heywatchlist.ui.theme.HeyTheme
import kotlin.math.abs

private val CardWidth = 124.dp
private const val HERO_COUNT = 6
private const val HERO_ASPECT_RATIO = 16f / 10f
private const val SKELETON_CARDS = 6

/**
 * Onglet Découvrir : recherche, carrousel des tendances, genres, puis les
 * rangées du catalogue. Les données viennent du [viewModel], qui survit au
 * changement d'onglet.
 */
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenTitle: (Title) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGenre: (Genre) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DiscoverContent(
        state = state,
        onRefresh = viewModel::refresh,
        onRetryShelf = viewModel::retry,
        onRetryGenres = viewModel::retryGenres,
        onOpenTitle = onOpenTitle,
        onOpenSearch = onOpenSearch,
        onOpenGenre = onOpenGenre,
    )
}

@Composable
internal fun DiscoverContent(
    state: DiscoverUiState,
    onRefresh: () -> Unit,
    onRetryShelf: (Shelf) -> Unit,
    onRetryGenres: () -> Unit,
    onOpenTitle: (Title) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGenre: (Genre) -> Unit,
) {
    val loading = state.genres is GenresState.Loading || state.shelves.values.any { it is ShelfState.Loading }
    val pulse = rememberSkeletonPulse(active = loading)
    CompositionLocalProvider(LocalSkeletonPulse provides pulse) {
        Column(modifier = Modifier.fillMaxSize()) {
            // En-tête fixe : le verre de la recherche ne se pose que sur le décor, pas sur une liste qui défile.
            Column(
                modifier = Modifier.screenPadding().padding(top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Pas de geste de tirer avec TalkBack : « Actualiser » est une action du titre de l'écran.
                val refreshLabel = stringResource(R.string.action_refresh)
                HeyText(
                    text = stringResource(R.string.tab_discover),
                    style = HeyTheme.typography.display,
                    modifier = Modifier.semantics {
                        heading()
                        customActions = listOf(CustomAccessibilityAction(refreshLabel) { onRefresh(); true })
                    },
                )
                HeySearchEntry(text = stringResource(R.string.search_placeholder), onClick = onOpenSearch)
            }
            HeyPullToRefresh(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                DiscoverList(state, onRetryShelf, onRetryGenres, onOpenTitle, onOpenGenre)
            }
        }
    }
}

@Composable
private fun DiscoverList(
    state: DiscoverUiState,
    onRetryShelf: (Shelf) -> Unit,
    onRetryGenres: () -> Unit,
    onOpenTitle: (Title) -> Unit,
    onOpenGenre: (Genre) -> Unit,
) {
    val trending = state.shelf(Shelf.TrendingWeek)
    val heroes = remember(trending) {
        (trending as? ShelfState.Loaded)?.titles.orEmpty().filter { it.backdropUrl != null }.take(HERO_COUNT)
    }
    // Une rangée lue mais vide (rien de lisible) disparaît au lieu d'afficher un titre sans rien dessous.
    val shelves = DiscoverShelves.filter { (state.shelf(it) as? ShelfState.Loaded)?.titles?.isEmpty() != true }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (state.isStale) {
            item(key = "stale") { StaleBanner(modifier = Modifier.screenPadding()) }
        }
        if (trending is ShelfState.Loading || heroes.isNotEmpty()) {
            item(key = "hero") {
                if (heroes.isEmpty()) HeroSkeleton() else HeroCarousel(heroes, onOpenTitle)
            }
        }
        item(key = "genres") { GenresSection(state.genres, onOpenGenre, onRetryGenres) }
        items(shelves, key = { it.name }) { shelf ->
            ShelfRow(shelf, state.shelf(shelf), onOpenTitle, onRetry = { onRetryShelf(shelf) })
        }
    }
}

@Composable
private fun HeroSkeleton() {
    Skeleton(
        modifier = Modifier.screenPadding().fillMaxWidth().aspectRatio(HERO_ASPECT_RATIO),
        shape = HeyTheme.shapes.card,
    )
}

@Composable
private fun HeroCarousel(titles: List<Title>, onOpen: (Title) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { titles.size })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            pageSpacing = 12.dp,
            key = { titles.getOrNull(it)?.key ?: it },
        ) { page ->
            HeroCard(
                title = titles[page],
                onOpen = onOpen,
                // Les affiches voisines reculent un peu : lu au dessin, sans recomposer.
                modifier = Modifier.graphicsLayer {
                    val distance = abs(pagerState.getOffsetDistanceInPages(page)).coerceAtMost(1f)
                    val scale = 1f - HERO_SIDE_SHRINK * distance
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.35f * distance
                },
            )
        }
        if (titles.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val animations = rememberAnimationsEnabled()
                titles.indices.forEach { index ->
                    val current = index == pagerState.currentPage
                    // La page affichée s'allonge en pastille.
                    val width by animateDpAsState(
                        targetValue = if (current) 20.dp else 6.dp,
                        animationSpec = if (animations) spring<Dp>(stiffness = Spring.StiffnessMediumLow) else snap<Dp>(),
                        label = "hero-dot",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = width, height = 6.dp)
                            .clip(CircleShape)
                            .background(if (current) HeyTheme.colors.primary else HeyTheme.colors.onBackgroundMuted.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}

/** Fond du titre, avec son nom et sa note sur un dégradé sombre (jamais directement sur l'image). */
@Composable
private fun HeroCard(title: Title, onOpen: (Title) -> Unit, modifier: Modifier = Modifier) {
    val meta = listOfNotNull(kindLabel(title.kind), title.year?.toString()).joinToString(META_SEPARATOR)
    val shape = HeyTheme.shapes.card
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(HERO_ASPECT_RATIO)
            .clip(shape)
            .clickable(role = Role.Button, onClick = { onOpen(title) }),
    ) {
        HeyPoster(url = title.backdropUrl, modifier = Modifier.fillMaxSize(), shape = RectangleShape)
        Scrim(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(HERO_SCRIM_HEIGHT))
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HeyText(
                text = title.title,
                style = HeyTheme.typography.headline,
                color = OnScrim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeyText(text = meta, style = HeyTheme.typography.caption, color = OnScrim)
                RatingBadge(rating = title.rating, voteCount = title.voteCount, color = OnScrim)
            }
        }
        // Reflet du verre sur le bord de la carte, par-dessus l'image.
        Box(modifier = Modifier.matchParentSize().border(1.dp, HeroEdge, shape))
    }
}

private const val HERO_SCRIM_HEIGHT = 0.7f
private const val HERO_SIDE_SHRINK = 0.06f
private val HeroEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.06f)))

@Composable
private fun GenresSection(state: GenresState, onOpenGenre: (Genre) -> Unit, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(text = stringResource(R.string.discover_genres), modifier = Modifier.screenPadding())
        when (state) {
            GenresState.Loading -> {
                val loading = stringResource(R.string.status_loading)
                Row(
                    modifier = Modifier.screenPadding().semantics { contentDescription = loading },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(3) { Skeleton(modifier = Modifier.size(width = 96.dp, height = 48.dp), shape = CircleShape) }
                }
            }

            is GenresState.Loaded -> LazyRow(
                contentPadding = PaddingValues(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.genres, key = { it.id }) { genre ->
                    HeyChip(text = genre.name, onClick = { onOpenGenre(genre) })
                }
            }

            is GenresState.Failed -> ErrorPanel(state.failure, onRetry, Modifier.screenPadding())
        }
    }
}

@Composable
private fun ShelfRow(shelf: Shelf, state: ShelfState, onOpen: (Title) -> Unit, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(text = stringResource(shelfTitleRes(shelf)), modifier = Modifier.screenPadding())
        when (state) {
            ShelfState.Loading -> {
                val loading = stringResource(R.string.status_loading)
                LazyRow(
                    modifier = Modifier.semantics { contentDescription = loading },
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                ) {
                    items(count = SKELETON_CARDS) { TitleCardSkeleton(modifier = Modifier.width(CardWidth)) }
                }
            }

            is ShelfState.Loaded -> {
                val movieLabel = kindLabel(MediaKind.Movie)
                val seriesLabel = kindLabel(MediaKind.Tv)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.titles, key = { it.key }) { title ->
                        val kind = if (title.kind == MediaKind.Movie) movieLabel else seriesLabel
                        TitleCard(
                            title = title,
                            onClick = { onOpen(title) },
                            modifier = Modifier.width(CardWidth),
                            caption = shelfCaption(shelf, title, kind),
                        )
                    }
                }
            }

            is ShelfState.Failed -> ErrorPanel(state.failure, onRetry, Modifier.screenPadding())
        }
    }
}

/**
 * Légende sous l'affiche : la date de sortie pour « À venir », le type pour les
 * tendances (qui mêlent films et séries), sinon l'année.
 */
private fun shelfCaption(shelf: Shelf, title: Title, kindLabel: String): String? = when (shelf) {
    Shelf.UpcomingMovies -> formatDate(title.releaseDate) ?: title.year?.toString()
    Shelf.TrendingWeek -> listOfNotNull(kindLabel, title.year?.toString()).joinToString(META_SEPARATOR)
    else -> title.year?.toString()
}

@StringRes
private fun shelfTitleRes(shelf: Shelf): Int = when (shelf) {
    Shelf.TrendingWeek -> R.string.shelf_trending
    Shelf.NowPlayingMovies -> R.string.shelf_now_playing
    Shelf.UpcomingMovies -> R.string.shelf_upcoming
    Shelf.PopularMovies -> R.string.shelf_popular_movies
    Shelf.PopularSeries -> R.string.shelf_popular_series
    Shelf.OnTheAirSeries -> R.string.shelf_on_the_air
    Shelf.TopRatedMovies -> R.string.shelf_top_rated_movies
    Shelf.TopRatedSeries -> R.string.shelf_top_rated_series
}
