package app.heywatchlist.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyPoster
import app.heywatchlist.ui.components.HeyTag
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.HeyTextField
import app.heywatchlist.ui.components.POSTER_ASPECT_RATIO
import app.heywatchlist.ui.theme.HeyTheme
import uniffi.heywatchlist_core.AdminRequest
import uniffi.heywatchlist_core.MediaType
import uniffi.heywatchlist_core.Release
import uniffi.heywatchlist_core.RequestStatus
import uniffi.heywatchlist_core.SourceReport

/**
 * Fiche d'une demande : qui la suit, recherche dans les sources, choix d'une
 * release envoyée à qBittorrent, refus ou réouverture. [onChanged] reçoit la
 * demande à jour après chaque action.
 */
@Composable
fun RequestScreen(viewModel: RequestViewModel, onBack: () -> Unit, onChanged: (AdminRequest) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val request = state.request
    LaunchedEffect(request) { onChanged(request) }
    val working = state.action == RequestAction.Working

    Column(modifier = Modifier.fillMaxSize()) {
        AdminHeader(title = request.title, onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            item { RequestSummary(request) }
            item {
                ActionCard(
                    request = request,
                    action = state.action,
                    onRefuse = viewModel::openRefusal,
                    onReopen = viewModel::reopen,
                    onMarkAvailable = viewModel::markAvailable,
                    onRetryStatus = viewModel::retryStatus,
                )
            }
            if (request.status != RequestStatus.AVAILABLE) {
                item {
                    SearchCard(
                        title = viewModel.searchTitle,
                        onTitleChange = { viewModel.searchTitle = it },
                        season = viewModel.season.takeIf { request.mediaType == MediaType.TV },
                        onSeasonChange = { viewModel.season = it.filter(Char::isDigit).take(3) },
                        running = state.search is ReleaseSearch.Running,
                        onSearch = viewModel::search,
                    )
                }
                searchResults(state.search, enabled = !working, onChoose = viewModel::choose)
            }
        }
    }

    state.confirming?.let { release ->
        AdminDialog(
            title = stringResource(R.string.admin_send_title),
            onDismiss = viewModel::cancelChoice,
        ) {
            ReleaseSummary(release)
            HeyButton(
                text = stringResource(R.string.admin_send_confirm),
                onClick = viewModel::send,
                modifier = Modifier.fillMaxWidth(),
            )
            HeyButton(
                text = stringResource(R.string.admin_cancel),
                onClick = viewModel::cancelChoice,
                kind = HeyButtonKind.Link,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.refusing) {
        AdminDialog(
            title = stringResource(R.string.admin_refuse_title),
            body = stringResource(R.string.admin_refuse_body),
            onDismiss = viewModel::closeRefusal,
        ) {
            HeyTextField(
                value = viewModel.refusalReason,
                onValueChange = { viewModel.refusalReason = it.take(RequestViewModel.MAX_REASON) },
                placeholder = stringResource(R.string.admin_refuse_placeholder),
                label = stringResource(R.string.admin_refuse_label),
            )
            HeyButton(
                text = stringResource(R.string.admin_refuse_confirm),
                onClick = viewModel::refuse,
                kind = HeyButtonKind.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
            HeyButton(
                text = stringResource(R.string.admin_cancel),
                onClick = viewModel::closeRefusal,
                kind = HeyButtonKind.Link,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RequestSummary(request: AdminRequest) {
    AdminCard {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HeyPoster(url = request.posterUrl, modifier = Modifier.width(96.dp).aspectRatio(POSTER_ASPECT_RATIO))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RequestStatusPill(request.status)
                HeyText(text = requestSubtitle(request), color = HeyTheme.colors.onBackgroundMuted)
                HeyText(text = followersLabel(request))
                HeyText(
                    text = stringResource(R.string.admin_requested_on, formatDate(request.createdAt)),
                    style = HeyTheme.typography.caption,
                    color = HeyTheme.colors.onBackgroundMuted,
                )
            }
        }
        request.releaseTitle?.takeIf { request.status == RequestStatus.IN_PROGRESS }?.let {
            HeyText(text = stringResource(R.string.admin_release_sent, it), style = HeyTheme.typography.caption)
        }
        request.refusalReason?.takeIf { request.status == RequestStatus.REFUSED }?.let {
            HeyText(text = stringResource(R.string.admin_refused_because, it), style = HeyTheme.typography.caption)
        }
    }
}

@Composable
private fun ActionCard(
    request: AdminRequest,
    action: RequestAction,
    onRefuse: () -> Unit,
    onReopen: () -> Unit,
    onMarkAvailable: () -> Unit,
    onRetryStatus: () -> Unit,
) {
    val canRefuse = request.status == RequestStatus.REQUESTED || request.status == RequestStatus.IN_PROGRESS
    val canReopen = request.status == RequestStatus.IN_PROGRESS || request.status == RequestStatus.REFUSED
    val canMarkAvailable = request.status != RequestStatus.AVAILABLE
    val working = action == RequestAction.Working
    if (!canRefuse && !canReopen && !canMarkAvailable && action == RequestAction.Idle) return
    AdminCard {
        when (action) {
            RequestAction.Working -> LoadingRow(stringResource(R.string.admin_working))
            RequestAction.Sent -> HeyText(text = stringResource(R.string.admin_sent))
            RequestAction.Refused -> HeyText(text = stringResource(R.string.admin_refused))
            RequestAction.Reopened -> HeyText(text = stringResource(R.string.admin_reopened))
            RequestAction.MarkedAvailable -> HeyText(text = stringResource(R.string.admin_marked_available))
            is RequestAction.Failed -> FailureText(action.failure)
            is RequestAction.NotSaved -> {
                FailureText(AdminFailure.StatusNotSaved)
                action.retryFailure?.let { FailureText(it) }
                HeyButton(
                    text = stringResource(R.string.admin_retry_status),
                    onClick = onRetryStatus,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RequestAction.Idle -> Unit
        }
        if (action !is RequestAction.NotSaved && (canRefuse || canReopen)) {
            ButtonRow { cell ->
                if (canReopen) {
                    HeyButton(
                        text = stringResource(R.string.admin_reopen),
                        onClick = onReopen,
                        kind = HeyButtonKind.Secondary,
                        enabled = !working,
                        modifier = cell,
                    )
                }
                if (canRefuse) {
                    HeyButton(
                        text = stringResource(R.string.admin_refuse),
                        onClick = onRefuse,
                        kind = HeyButtonKind.Danger,
                        enabled = !working,
                        modifier = cell,
                    )
                }
            }
        }
        if (action !is RequestAction.NotSaved && canMarkAvailable) {
            // Titre déjà sur Plex avant les webhooks, ou arrivée qu'ils n'ont pas vue.
            HeyButton(
                text = stringResource(R.string.admin_mark_available),
                onClick = onMarkAvailable,
                kind = HeyButtonKind.Secondary,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchCard(
    title: String,
    onTitleChange: (String) -> Unit,
    season: String?,
    onSeasonChange: (String) -> Unit,
    running: Boolean,
    onSearch: () -> Unit,
) {
    AdminCard {
        HeyText(text = stringResource(R.string.admin_search_title), style = HeyTheme.typography.title)
        HeyTextField(
            value = title,
            onValueChange = onTitleChange,
            placeholder = stringResource(R.string.admin_search_placeholder),
            label = stringResource(R.string.admin_search_label),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        if (season != null) {
            HeyTextField(
                value = season,
                onValueChange = onSeasonChange,
                placeholder = stringResource(R.string.admin_season_placeholder),
                label = stringResource(R.string.admin_season_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
        }
        HeyButton(
            text = stringResource(if (running) R.string.admin_searching else R.string.admin_search),
            onClick = onSearch,
            enabled = !running && title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun LazyListScope.searchResults(
    search: ReleaseSearch,
    enabled: Boolean,
    onChoose: (Release) -> Unit,
) {
    val (releases, reports) = when (search) {
        ReleaseSearch.Idle -> return
        is ReleaseSearch.Failed -> {
            item { AdminCard { FailureText(search.failure) } }
            return
        }
        is ReleaseSearch.Running -> search.releases to search.reports
        is ReleaseSearch.Done -> search.releases to search.reports
    }
    item {
        SearchStatus(
            reports = reports,
            pending = (search as? ReleaseSearch.Running)?.pending,
            count = releases.size,
        )
    }
    items(releases, key = { it.id }) { release ->
        ReleaseRow(release = release, enabled = enabled, onClick = { onChoose(release) })
    }
}

/** Avancement, nombre de résultats et sources en échec. */
@Composable
private fun SearchStatus(reports: List<SourceReport>, pending: Int?, count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pending != null) {
            LoadingRow(
                if (pending > 0) {
                    pluralStringResource(R.plurals.admin_search_pending, pending, pending)
                } else {
                    stringResource(R.string.admin_searching)
                },
            )
        } else {
            HeyText(
                text = if (count == 0) stringResource(R.string.admin_search_none) else pluralStringResource(R.plurals.admin_search_count, count, count),
                color = HeyTheme.colors.onBackgroundMuted,
            )
        }
        reports.forEach { report ->
            report.failure?.let { failure ->
                HeyText(
                    text = stringResource(R.string.admin_source_failed, report.sourceName, stringResource(failure.labelRes())),
                    style = HeyTheme.typography.caption,
                    color = HeyTheme.colors.error,
                )
            }
        }
    }
}

@Composable
private fun ReleaseRow(release: Release, enabled: Boolean, onClick: () -> Unit) {
    AdminCard(onClick = onClick.takeIf { enabled }) {
        HeyText(text = softBreaks(release.title), style = HeyTheme.typography.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        ReleaseTags(release)
        HeyText(
            text = releaseDetails(release),
            style = HeyTheme.typography.caption,
            color = HeyTheme.colors.onBackgroundMuted,
        )
    }
}

/** Contenu de la feuille d'envoi : la qualité en pastilles, les chiffres, puis le nom brut en petit. */
@Composable
private fun ReleaseSummary(release: Release) {
    ReleaseTags(release)
    HeyText(text = releaseDetails(release), style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
    HeyText(text = softBreaks(release.title), style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseTags(release: Release) {
    val tags = release.info.tags()
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { HeyTag(text = stringResource(it)) }
    }
}

/**
 * Nom brut d'une release, coupable en fin de ligne après chaque point, tiret
 * ou soulignement (espace sans largeur) : sinon il se coupe au milieu d'un mot.
 */
internal fun softBreaks(name: String): String = buildString(name.length + name.length / 4) {
    name.forEach { char ->
        append(char)
        if (char == '.' || char == '-' || char == '_') append('\u200B')
    }
}

/** « 4,2 Go · 120 sources · Indexeur · 3 nov. 2026 ». */
@Composable
private fun releaseDetails(release: Release): String {
    val parts = mutableListOf<String>()
    release.sizeBytes?.let { parts += bytesLabel(it.toLong()) }
    release.seeders?.let { parts += pluralStringResource(R.plurals.admin_seeders, it.toInt(), it.toInt()) }
    parts += release.indexer?.takeIf { it.isNotBlank() } ?: release.sourceName
    release.publishedAt?.let { parts += formatDate(it) }
    return parts.joinToString(" · ")
}
