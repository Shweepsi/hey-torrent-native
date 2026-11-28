package app.heywatchlist.ui.discover

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.core.CastMember
import app.heywatchlist.core.Episode
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Provider
import app.heywatchlist.core.Providers
import app.heywatchlist.core.SeasonSummary
import app.heywatchlist.core.Title
import app.heywatchlist.core.TitleDetail
import app.heywatchlist.core.Video
import app.heywatchlist.ui.components.ErrorPanel
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIconButton
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyPoster
import app.heywatchlist.ui.components.HeyTag
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.components.LocalSkeletonPulse
import app.heywatchlist.ui.components.POSTER_ASPECT_RATIO
import app.heywatchlist.ui.components.Skeleton
import app.heywatchlist.ui.components.StaleBanner
import app.heywatchlist.ui.components.TitleCard
import app.heywatchlist.ui.components.rememberSkeletonPulse
import app.heywatchlist.ui.theme.HeyTheme
import app.heywatchlist.ui.watchlist.WatchlistPanel
import app.heywatchlist.ui.watchlist.WatchlistUiState
import app.heywatchlist.ui.watchlist.WatchlistViewModel
import app.heywatchlist.ui.watchlist.watchlistKey

private val BackdropHeight = 300.dp
private val BackdropHeightWithoutImage = 190.dp
private val PosterWidth = 112.dp
private val SectionGap = 24.dp
private val CardWidth = 124.dp
private val AmbientBlur = 72.dp
private const val PARALLAX = 0.45f

/** Masque du fond du titre : opaque en haut, transparent en bas, où la fiche reprend. */
private val BackdropMask = Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent)
private const val EXPANDED_CHEVRON_DEGREES = 180f

/**
 * Fiche plein écran d'un film ou d'une série. Elle est toujours sombre, quel que
 * soit le thème : le fond du titre se fond dans un dégradé sombre, sous le texte.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    watchlist: WatchlistViewModel,
    onBack: () -> Unit,
    onOpenTitle: (Title) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val watchlistState by watchlist.uiState.collectAsStateWithLifecycle()
    DetailContent(
        state = state,
        watchlist = watchlistState,
        onBack = onBack,
        onOpenTitle = onOpenTitle,
        onRetry = viewModel::retry,
        onToggleSeason = viewModel::toggleSeason,
        onRetrySeason = viewModel::retrySeason,
        onAddToWatchlist = { seasons -> watchlist.add(state.preview, seasons) },
        onRemoveFromWatchlist = { watchlist.remove(state.preview.kind, state.preview.id) },
    )
}

@Composable
internal fun DetailContent(
    state: DetailUiState,
    watchlist: WatchlistUiState,
    onBack: () -> Unit,
    onOpenTitle: (Title) -> Unit,
    onRetry: () -> Unit,
    onToggleSeason: (Int) -> Unit,
    onRetrySeason: (Int) -> Unit,
    onAddToWatchlist: (seasons: List<Int>?) -> Unit,
    onRemoveFromWatchlist: () -> Unit,
) {
    // Fond opaque : pas de décor à flouter derrière, le verre devient une surface sans flou.
    CompositionLocalProvider(LocalHazeState provides null) {
        val pulse = rememberSkeletonPulse(active = state is DetailUiState.Loading)
        CompositionLocalProvider(LocalSkeletonPulse provides pulse) {
            Box(modifier = Modifier.fillMaxSize().background(HeyTheme.colors.background)) {
                AmbientArtwork(url = state.preview.posterUrl ?: state.preview.backdropUrl)
                DetailList(
                    state = state,
                    watchlist = watchlist,
                    onOpenTitle = onOpenTitle,
                    onRetry = onRetry,
                    onToggleSeason = onToggleSeason,
                    onRetrySeason = onRetrySeason,
                    onAddToWatchlist = onAddToWatchlist,
                    onRemoveFromWatchlist = onRemoveFromWatchlist,
                )
                HeyIconButton(
                    icon = HeyIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
                )
            }
        }
    }
}

/**
 * Fond de toute la fiche : l'affiche du titre, très floutée et voilée, qui
 * teinte la page à ses couleurs. Elle ne défile pas : le flou n'est calculé
 * qu'une fois, pas à chaque image du défilement.
 */
@Composable
private fun AmbientArtwork(url: String?) {
    val background = HeyTheme.colors.background
    Box(modifier = Modifier.fillMaxSize()) {
        if (url != null) {
            HeyPoster(
                url = url,
                modifier = Modifier.fillMaxSize().blur(AmbientBlur, BlurredEdgeTreatment.Rectangle),
                shape = RectangleShape,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to background.copy(alpha = 0.5f),
                    0.5f to background.copy(alpha = 0.7f),
                    1f to background.copy(alpha = 0.86f),
                ),
            ),
        )
    }
}

@Composable
private fun DetailList(
    state: DetailUiState,
    watchlist: WatchlistUiState,
    onOpenTitle: (Title) -> Unit,
    onRetry: () -> Unit,
    onToggleSeason: (Int) -> Unit,
    onRetrySeason: (Int) -> Unit,
    onAddToWatchlist: (seasons: List<Int>?) -> Unit,
    onRemoveFromWatchlist: () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + SectionGap),
    ) {
        item(key = "header") {
            DetailHeader(
                title = state.preview,
                detail = (state as? DetailUiState.Loaded)?.detail,
                // Lu au dessin seulement : le défilement ne recompose pas l'en-tête.
                scrollOffset = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else 0 },
            )
        }
        item(key = "watchlist") {
            val title = state.preview
            WatchlistPanel(
                title = title,
                entry = watchlist.entryFor(title.kind, title.id),
                // En échec, la série se demande entière ; pendant le chargement, le choix attend.
                seasons = when (state) {
                    is DetailUiState.Loaded -> state.detail.seasons
                    is DetailUiState.Failed -> emptyList()
                    is DetailUiState.Loading -> null
                },
                busy = watchlistKey(title.kind, title.id) in watchlist.busy,
                onAdd = onAddToWatchlist,
                onRemove = onRemoveFromWatchlist,
                modifier = Modifier.screenPadding().padding(top = SectionGap),
            )
        }
        when (state) {
            is DetailUiState.Loading -> item(key = "loading") { DetailSkeleton() }
            is DetailUiState.Failed -> item(key = "failed") {
                ErrorPanel(state.failure, onRetry, Modifier.screenPadding().padding(top = SectionGap))
            }

            is DetailUiState.Loaded -> loadedSections(state, onOpenTitle, onToggleSeason, onRetrySeason)
        }
    }
}

private fun LazyListScope.loadedSections(
    state: DetailUiState.Loaded,
    onOpenTitle: (Title) -> Unit,
    onToggleSeason: (Int) -> Unit,
    onRetrySeason: (Int) -> Unit,
) {
    val detail = state.detail
    if (detail.stale) {
        item(key = "stale") { StaleBanner(Modifier.screenPadding().padding(top = SectionGap)) }
    }
    detail.trailer?.let { trailer ->
        item(key = "trailer") { TrailerButton(trailer, Modifier.screenPadding().padding(top = SectionGap)) }
    }
    item(key = "overview") { OverviewSection(detail, Modifier.padding(top = SectionGap)) }
    item(key = "providers") { ProvidersSection(detail.providers, Modifier.padding(top = SectionGap)) }

    val seasons = detail.seasons.distinctBy { it.number }
    if (detail.summary.kind == MediaKind.Tv && seasons.isNotEmpty()) {
        item(key = "seasons-title") {
            SectionTitle(
                text = stringResource(R.string.detail_seasons_title),
                modifier = Modifier.screenPadding().padding(top = SectionGap, bottom = 4.dp),
            )
        }
        seasons.forEach { season -> seasonItems(season, state, onToggleSeason, onRetrySeason) }
    }

    if (detail.cast.isNotEmpty()) {
        item(key = "cast") { CastSection(detail.cast, Modifier.padding(top = SectionGap)) }
    }
    if (detail.similar.isNotEmpty()) {
        item(key = "similar") { SimilarSection(detail.similar, onOpenTitle, Modifier.padding(top = SectionGap)) }
    }
}

/** Une saison, et ses épisodes si elle est ouverte. */
private fun LazyListScope.seasonItems(
    season: SeasonSummary,
    state: DetailUiState.Loaded,
    onToggleSeason: (Int) -> Unit,
    onRetrySeason: (Int) -> Unit,
) {
    val expanded = state.expandedSeason == season.number
    val prefix = "season-${season.number}"
    item(key = prefix) { SeasonRow(season, expanded, onClick = { onToggleSeason(season.number) }) }
    if (!expanded) return
    when (val episodes = state.seasons[season.number]) {
        null, SeasonState.Loading -> item(key = "$prefix-loading") { EpisodesSkeleton() }
        is SeasonState.Failed -> item(key = "$prefix-failed") {
            ErrorPanel(episodes.failure, { onRetrySeason(season.number) }, Modifier.screenPadding().padding(vertical = 8.dp))
        }

        is SeasonState.Loaded -> {
            if (episodes.season.stale) {
                item(key = "$prefix-stale") { StaleBanner(Modifier.screenPadding().padding(vertical = 8.dp)) }
            }
            val overview = episodes.season.overview
            if (overview.isNotBlank()) {
                item(key = "$prefix-overview") {
                    HeyText(
                        text = overview,
                        color = HeyTheme.colors.onBackgroundMuted,
                        modifier = Modifier.screenPadding().padding(vertical = 8.dp),
                    )
                }
            }
            if (episodes.season.episodes.isEmpty()) {
                item(key = "$prefix-empty") {
                    HeyText(
                        text = stringResource(R.string.detail_no_episodes),
                        color = HeyTheme.colors.onBackgroundMuted,
                        modifier = Modifier.screenPadding().padding(vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(episodes.season.episodes, key = { index, _ -> "$prefix-episode-$index" }) { _, episode ->
                    EpisodeRow(episode)
                }
            }
        }
    }
}

/**
 * Fond du titre, qui défile moins vite que la page (parallaxe) et s'efface vers
 * le bas dans le fond de la fiche, avec l'affiche, le nom, l'année, la durée et
 * la note posés sur un dégradé sombre ; les genres en dessous.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeader(title: Title, detail: TitleDetail?, scrollOffset: () -> Int) {
    val colors = HeyTheme.colors
    val meta = buildList {
        add(kindLabel(title.kind))
        title.year?.let { add(it.toString()) }
        detail?.seasonsCount?.takeIf { it > 0 && title.kind == MediaKind.Tv }
            ?.let { add(pluralString(R.plurals.detail_seasons, it, it)) }
        detail?.runtimeMinutes?.takeIf { it > 0 }?.let { add(runtimeLabel(it)) }
    }.joinToString(META_SEPARATOR)
    Column {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (title.backdropUrl != null) BackdropHeight else BackdropHeightWithoutImage),
            ) {
                HeyPoster(
                    url = title.backdropUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = scrollOffset() * PARALLAX
                            // Rendu à part pour que le masque ne touche que l'image.
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(brush = BackdropMask, blendMode = BlendMode.DstIn)
                        },
                    shape = RectangleShape,
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to colors.background.copy(alpha = 0.55f),
                            0.22f to Color.Transparent,
                            0.5f to colors.background.copy(alpha = 0.35f),
                            1f to colors.background.copy(alpha = 0.6f),
                        ),
                    ),
                )
            }
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                HeyPoster(
                    url = title.posterUrl,
                    modifier = Modifier
                        .width(PosterWidth)
                        .aspectRatio(POSTER_ASPECT_RATIO)
                        .shadow(elevation = 18.dp, shape = HeyTheme.shapes.poster)
                        .border(1.dp, Color.White.copy(alpha = 0.22f), HeyTheme.shapes.poster),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeyText(
                        text = title.title,
                        style = HeyTheme.typography.headline,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HeyText(text = meta, style = HeyTheme.typography.caption, color = colors.onBackgroundMuted)
                    RatingBadge(rating = title.rating, voteCount = title.voteCount)
                    if (title.voteCount > 0) {
                        HeyText(
                            text = pluralString(R.plurals.detail_votes, title.voteCount, formatCount(title.voteCount)),
                            style = HeyTheme.typography.caption,
                            color = colors.onBackgroundMuted,
                        )
                    }
                }
            }
        }
        if (detail != null && detail.genres.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.screenPadding().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                detail.genres.forEach { HeyTag(text = it.name) }
            }
        }
    }
}

@Composable
private fun TrailerButton(video: Video, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.detail_trailer_unavailable)
    HeyButton(
        text = stringResource(R.string.detail_trailer),
        icon = HeyIcons.Play,
        modifier = modifier.fillMaxWidth(),
        onClick = {
            // YouTube s'ouvre dans l'app ou le navigateur du système.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl(video.youtubeKey)))
            try {
                context.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
private fun OverviewSection(detail: TitleDetail, modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    val overview = detail.summary.overview
    Column(modifier = modifier.screenPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.tagline?.takeIf { it.isNotBlank() }?.let {
            HeyText(text = it, style = HeyTheme.typography.label, color = colors.onBackgroundMuted)
        }
        SectionTitle(text = stringResource(R.string.detail_overview))
        if (overview.isBlank()) {
            HeyText(text = stringResource(R.string.detail_overview_empty), color = colors.onBackgroundMuted)
        } else {
            HeyText(text = overview)
        }
        if (detail.directors.isNotEmpty()) {
            val label = if (detail.summary.kind == MediaKind.Movie) R.string.detail_directed_by else R.string.detail_created_by
            HeyText(
                text = stringResource(label, detail.directors.joinToString(", ")),
                style = HeyTheme.typography.caption,
                color = colors.onBackgroundMuted,
            )
        }
    }
}

@Composable
private fun ProvidersSection(providers: Providers, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(text = stringResource(R.string.detail_where_to_watch), modifier = Modifier.screenPadding())
        if (providers.isEmpty) {
            HeyText(
                text = stringResource(R.string.detail_providers_none),
                color = HeyTheme.colors.onBackgroundMuted,
                modifier = Modifier.screenPadding(),
            )
        } else {
            ProviderGroup(R.string.detail_providers_flatrate, providers.flatrate)
            ProviderGroup(R.string.detail_providers_rent, providers.rent)
            ProviderGroup(R.string.detail_providers_buy, providers.buy)
        }
        // TMDB impose cette mention pour les données de disponibilité : elle accompagne toujours la section.
        HeyText(
            text = stringResource(R.string.detail_providers_attribution),
            style = HeyTheme.typography.caption,
            color = HeyTheme.colors.onBackgroundMuted,
            modifier = Modifier.screenPadding(),
        )
    }
}

@Composable
private fun ProviderGroup(@StringRes label: Int, providers: List<Provider>) {
    if (providers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HeyText(
            text = stringResource(label),
            style = HeyTheme.typography.caption,
            color = HeyTheme.colors.onBackgroundMuted,
            modifier = Modifier.screenPadding(),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(providers) { provider ->
                Column(modifier = Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HeyPoster(
                        url = provider.logoUrl,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                    HeyText(
                        text = provider.name,
                        style = HeyTheme.typography.caption,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonRow(season: SeasonSummary, expanded: Boolean, onClick: () -> Unit) {
    val name = season.name.ifBlank { stringResource(R.string.detail_season_default, season.number) }
    val meta = listOfNotNull(
        pluralString(R.plurals.detail_episodes, season.episodeCount, season.episodeCount),
        yearOf(season.airDate)?.toString(),
    ).joinToString(META_SEPARATOR)
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = state }
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeyPoster(url = season.posterUrl, modifier = Modifier.width(44.dp).aspectRatio(POSTER_ASPECT_RATIO))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HeyText(text = name, style = HeyTheme.typography.label)
            HeyText(text = meta, style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
        }
        HeyIcon(
            icon = HeyIcons.ChevronDown,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) EXPANDED_CHEVRON_DEGREES else 0f),
        )
    }
}

@Composable
private fun EpisodeRow(episode: Episode) {
    val name = episode.name.ifBlank { stringResource(R.string.detail_episode_default, episode.number) }
    val meta = listOfNotNull(episode.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeLabel(it) }, formatDate(episode.airDate))
        .joinToString(META_SEPARATOR)
    Row(
        modifier = Modifier.fillMaxWidth().screenPadding().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeyPoster(url = episode.stillUrl, modifier = Modifier.width(120.dp).aspectRatio(STILL_ASPECT_RATIO))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HeyText(text = "${episode.number}. $name", style = HeyTheme.typography.label)
            if (meta.isNotEmpty()) {
                HeyText(text = meta, style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
            }
            if (episode.overview.isNotBlank()) {
                HeyText(
                    text = episode.overview,
                    style = HeyTheme.typography.caption,
                    color = HeyTheme.colors.onBackgroundMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val STILL_ASPECT_RATIO = 16f / 9f

@Composable
private fun CastSection(cast: List<CastMember>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(text = stringResource(R.string.detail_cast), modifier = Modifier.screenPadding())
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cast) { member ->
                Column(
                    modifier = Modifier.width(88.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HeyPoster(
                        url = member.profileUrl,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        placeholderIcon = HeyIcons.Person,
                    )
                    HeyText(
                        text = member.name,
                        style = HeyTheme.typography.caption,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    member.character?.takeIf { it.isNotBlank() }?.let {
                        HeyText(
                            text = it,
                            style = HeyTheme.typography.caption,
                            color = HeyTheme.colors.onBackgroundMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarSection(titles: List<Title>, onOpenTitle: (Title) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(text = stringResource(R.string.detail_similar), modifier = Modifier.screenPadding())
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(titles.distinctBy { it.key }, key = { it.key }) { title ->
                TitleCard(
                    title = title,
                    onClick = { onOpenTitle(title) },
                    modifier = Modifier.width(CardWidth),
                    caption = title.year?.toString(),
                )
            }
        }
    }
}

@Composable
private fun DetailSkeleton() {
    val loading = stringResource(R.string.status_loading)
    Column(
        modifier = Modifier.screenPadding().padding(top = SectionGap).semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Skeleton(modifier = Modifier.fillMaxWidth().height(48.dp), shape = HeyTheme.shapes.button)
        Skeleton(modifier = Modifier.fillMaxWidth().height(16.dp), shape = HeyTheme.shapes.button)
        Skeleton(modifier = Modifier.fillMaxWidth().height(16.dp), shape = HeyTheme.shapes.button)
        Skeleton(modifier = Modifier.fillMaxWidth(0.6f).height(16.dp), shape = HeyTheme.shapes.button)
    }
}

@Composable
private fun EpisodesSkeleton() {
    val loading = stringResource(R.string.status_loading)
    Column(
        modifier = Modifier.screenPadding().padding(vertical = 8.dp).semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Skeleton(modifier = Modifier.width(120.dp).aspectRatio(STILL_ASPECT_RATIO))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(modifier = Modifier.fillMaxWidth(0.7f).height(14.dp), shape = HeyTheme.shapes.button)
                    Skeleton(modifier = Modifier.fillMaxWidth().height(12.dp), shape = HeyTheme.shapes.button)
                }
            }
        }
    }
}
