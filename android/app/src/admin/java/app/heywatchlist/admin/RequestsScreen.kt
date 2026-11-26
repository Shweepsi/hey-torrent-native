package app.heywatchlist.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyPoster
import app.heywatchlist.ui.components.HeyPullToRefresh
import app.heywatchlist.ui.components.HeySegmented
import app.heywatchlist.ui.components.HeyTag
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.POSTER_ASPECT_RATIO
import app.heywatchlist.ui.theme.HeyTheme
import uniffi.heywatchlist_core.AdminRequest
import uniffi.heywatchlist_core.MediaType

/** Demandes de tous les comptes, les plus anciennes d'abord. */
@Composable
fun RequestsScreen(viewModel: RequestsViewModel, onOpen: (AdminRequest) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        AdminHeader(title = stringResource(R.string.admin_tab_requests))
        HeySegmented(
            options = listOf(stringResource(R.string.admin_requests_open), stringResource(R.string.admin_requests_all)),
            selectedIndex = state.filter.ordinal,
            onSelect = { viewModel.setFilter(RequestsFilter.entries[it]) },
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        )
        HeyPullToRefresh(
            refreshing = state.loading && state.requests.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val failure = state.failure
                when {
                    failure != null -> item { FailurePanel(failure, onRetry = viewModel::refresh) }
                    state.loading && state.requests.isEmpty() -> item { LoadingRow(stringResource(R.string.status_loading)) }
                    state.requests.isEmpty() -> item {
                        EmptyText(
                            stringResource(
                                if (state.filter == RequestsFilter.Open) R.string.admin_requests_none_open else R.string.admin_requests_none,
                            ),
                        )
                    }
                }
                items(state.requests, key = { it.id }) { request ->
                    RequestRow(request = request, onClick = { onOpen(request) })
                }
            }
        }
    }
}

@Composable
private fun RequestRow(request: AdminRequest, onClick: () -> Unit) {
    AdminCard(modifier = Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HeyPoster(url = request.posterUrl, modifier = Modifier.width(56.dp).aspectRatio(POSTER_ASPECT_RATIO))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeyText(
                    text = request.title,
                    style = HeyTheme.typography.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                HeyText(text = requestSubtitle(request), color = HeyTheme.colors.onBackgroundMuted, maxLines = 2)
                HeyText(text = followersLabel(request), style = HeyTheme.typography.caption, maxLines = 2)
                HeyTag(text = stringResource(request.status.labelRes()))
            }
        }
    }
}

/** « Film · 2001 » ou « Série · 2008 · saisons 1, 2 ». */
@Composable
fun requestSubtitle(request: AdminRequest): String {
    val parts = mutableListOf(
        stringResource(if (request.mediaType == MediaType.MOVIE) R.string.kind_movie else R.string.kind_series),
    )
    request.year?.let { parts += it.toString() }
    if (request.mediaType == MediaType.TV) {
        val seasons = request.seasons
        parts += if (seasons.isNullOrEmpty()) {
            stringResource(R.string.admin_seasons_all)
        } else {
            pluralStringResource(R.plurals.admin_seasons_some, seasons.size, seasons.sorted().joinToString(", "))
        }
    }
    return parts.joinToString(" · ")
}

/** « Demandé par Léa et 2 autres ». */
@Composable
fun followersLabel(request: AdminRequest): String {
    val names = request.followers.mapNotNull { it.displayName?.takeIf(String::isNotBlank) }
    val count = request.followers.size
    return when {
        count == 0 -> stringResource(R.string.admin_followers_none)
        names.isEmpty() -> pluralStringResource(R.plurals.admin_followers_anonymous, count, count)
        count == 1 -> stringResource(R.string.admin_followers_one, names.first())
        else -> pluralStringResource(R.plurals.admin_followers_many, count - 1, names.first(), count - 1)
    }
}
