package app.heywatchlist.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.core.Title
import app.heywatchlist.ui.components.ErrorPanel
import app.heywatchlist.ui.components.FloatingHeaderLayout
import app.heywatchlist.ui.components.GlassBar
import app.heywatchlist.ui.components.HeyIconButton
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyPoster
import app.heywatchlist.ui.components.HeySearchField
import app.heywatchlist.ui.components.HeySpinner
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.components.LocalSkeletonPulse
import app.heywatchlist.ui.components.POSTER_ASPECT_RATIO
import app.heywatchlist.ui.components.Skeleton
import app.heywatchlist.ui.components.rememberSkeletonPulse
import app.heywatchlist.ui.components.scrolledFraction
import app.heywatchlist.ui.components.underBars
import app.heywatchlist.ui.components.underHeaderPadding
import app.heywatchlist.ui.theme.HeyTheme

private val RowPosterWidth = 72.dp
private const val SKELETON_ROWS = 6

/** Recherche instantanée de films et de séries, ouverte depuis le champ de l'onglet Découvrir. */
@Composable
fun SearchScreen(viewModel: SearchViewModel, onBack: () -> Unit, onOpenTitle: (Title) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Le texte du champ vit dans l'écran : le faire passer par un flux asynchrone ferait sauter des frappes.
    var text by rememberSaveable { mutableStateOf(state.query) }
    SearchContent(
        text = text,
        onTextChange = {
            text = it
            viewModel.onQueryChange(it)
        },
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onOpenTitle = onOpenTitle,
    )
}

@Composable
internal fun SearchContent(
    text: String,
    onTextChange: (String) -> Unit,
    state: SearchUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenTitle: (Title) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Clavier ouvert d'emblée, mais pas en revenant d'une fiche : la requête est déjà là.
    LaunchedEffect(Unit) {
        if (text.isEmpty()) {
            try {
                focusRequester.requestFocus()
            } catch (error: IllegalStateException) {
                // Le champ n'est pas encore posé : la personne touchera le champ.
            }
        }
    }
    val pulse = rememberSkeletonPulse(active = state.state is SearchState.Loading)
    // Une nouvelle requête repart du haut de la liste.
    val listState = rememberSaveable(state.query, saver = LazyListState.Saver) { LazyListState() }
    val haze = LocalHazeState.current
    CompositionLocalProvider(LocalSkeletonPulse provides pulse) {
        // En-tête flottant : les résultats défilent dessous, il les floute.
        FloatingHeaderLayout(
            header = {
                GlassBar(revealed = { if (state.state is SearchState.Results) listState.scrolledFraction() else 0f }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HeyIconButton(
                            icon = HeyIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                            onClick = onBack,
                        )
                        HeySearchField(
                            value = text,
                            onValueChange = onTextChange,
                            placeholder = stringResource(R.string.search_placeholder),
                            label = stringResource(R.string.search_label),
                            clearLabel = stringResource(R.string.search_clear),
                            modifier = Modifier.weight(1f),
                            focusRequester = focusRequester,
                        )
                    }
                }
            },
        ) { headerHeight ->
            val padding = underHeaderPadding(headerHeight)
            Box(modifier = Modifier.fillMaxSize()) {
                when (val current = state.state) {
                    SearchState.Idle -> Message(text = stringResource(R.string.search_hint), padding = padding)
                    SearchState.Loading -> SkeletonResults(padding)
                    is SearchState.Failed -> ErrorPanel(
                        failure = current.failure,
                        onRetry = onRetry,
                        modifier = Modifier.padding(padding).padding(top = 8.dp),
                    )

                    is SearchState.Results -> if (current.titles.isEmpty()) {
                        Message(text = stringResource(R.string.search_no_results, state.query.trim()), padding = padding)
                    } else {
                        Results(current, listState, padding, onLoadMore, onOpenTitle, Modifier.underBars(haze))
                    }
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
private fun Results(
    results: SearchState.Results,
    listState: LazyListState,
    padding: PaddingValues,
    onLoadMore: () -> Unit,
    onOpenTitle: (Title) -> Unit,
    modifier: Modifier = Modifier,
) {
    NearEndEffect(listState = listState, key = results.titles.size, onNearEnd = onLoadMore)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(results.titles, key = { it.key }) { title -> ResultRow(title, onClick = { onOpenTitle(title) }) }
        if (results.loadingMore) {
            item(key = "loading-more") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    HeySpinner(description = stringResource(R.string.status_loading))
                }
            }
        }
        results.moreFailure?.let { failure ->
            item(key = "more-failure") { ErrorPanel(failure = failure, onRetry = onLoadMore) }
        }
    }
}

/** Un résultat : affiche, nom, type et année, début du synopsis. Le texte est à côté de l'affiche, pas dessus. */
@Composable
private fun ResultRow(title: Title, onClick: () -> Unit) {
    val meta = listOfNotNull(kindLabel(title.kind), title.year?.toString()).joinToString(META_SEPARATOR)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeyPoster(url = title.posterUrl, modifier = Modifier.width(RowPosterWidth).aspectRatio(POSTER_ASPECT_RATIO))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeyText(
                text = title.title,
                style = HeyTheme.typography.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HeyText(text = meta, style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
            RatingBadge(rating = title.rating, voteCount = title.voteCount)
            if (title.overview.isNotBlank()) {
                HeyText(
                    text = title.overview,
                    style = HeyTheme.typography.caption,
                    color = HeyTheme.colors.onBackgroundMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SkeletonResults(padding: PaddingValues) {
    val loading = stringResource(R.string.status_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(SKELETON_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Skeleton(modifier = Modifier.width(RowPosterWidth).aspectRatio(POSTER_ASPECT_RATIO))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(modifier = Modifier.fillMaxWidth(0.7f).height(16.dp), shape = HeyTheme.shapes.button)
                    Skeleton(modifier = Modifier.fillMaxWidth(0.4f).height(12.dp), shape = HeyTheme.shapes.button)
                    Skeleton(modifier = Modifier.fillMaxWidth().height(12.dp), shape = HeyTheme.shapes.button)
                }
            }
        }
    }
}
