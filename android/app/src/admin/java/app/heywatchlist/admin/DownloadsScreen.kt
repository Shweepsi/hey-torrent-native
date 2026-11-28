package app.heywatchlist.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.heywatchlist.R
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyPullToRefresh
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme
import kotlinx.coroutines.delay
import uniffi.heywatchlist_core.Download
import uniffi.heywatchlist_core.DownloadState

/** Téléchargements en cours dans qBittorrent, relus toutes les 5 secondes tant que l'écran est visible. */
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(DownloadsViewModel.POLL_MILLIS)
                viewModel.refresh(quiet = true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AdminHeader(title = stringResource(R.string.admin_tab_downloads))
        HeyPullToRefresh(
            refreshing = state.loading && state.downloads.isNotEmpty(),
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.actionFailure?.let { failure -> item { AdminCard { FailureText(failure) } } }
                val failure = state.failure
                when {
                    failure != null -> item { FailurePanel(failure, onRetry = { viewModel.refresh() }) }
                    state.loading && state.downloads.isEmpty() -> item { LoadingRow(stringResource(R.string.status_loading)) }
                    state.downloads.isEmpty() -> item { EmptyText(stringResource(R.string.admin_downloads_none)) }
                }
                items(state.downloads, key = { it.hash }) { download ->
                    DownloadRow(
                        download = download,
                        busy = download.hash in state.busy,
                        onPause = { viewModel.pause(download) },
                        onResume = { viewModel.resume(download) },
                        onDelete = { viewModel.askDelete(download) },
                    )
                }
            }
        }
    }

    state.deleting?.let { download ->
        AdminDialog(
            title = stringResource(R.string.admin_delete_title),
            body = download.name,
            onDismiss = viewModel::cancelDelete,
        ) {
            HeyButton(
                text = stringResource(R.string.admin_delete_keep_files),
                onClick = { viewModel.delete(deleteFiles = false) },
                kind = HeyButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            HeyButton(
                text = stringResource(R.string.admin_delete_with_files),
                onClick = { viewModel.delete(deleteFiles = true) },
                kind = HeyButtonKind.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
            HeyButton(
                text = stringResource(R.string.admin_cancel),
                onClick = viewModel::cancelDelete,
                kind = HeyButtonKind.Link,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DownloadRow(download: Download, busy: Boolean, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    AdminCard {
        HeyText(text = download.name, maxLines = 3)
        ProgressBar(progress = download.progress.toFloat())
        HeyText(text = downloadDetails(download), style = HeyTheme.typography.caption, color = HeyTheme.colors.onBackgroundMuted)
        ButtonRow { cell ->
            if (download.state.isActive()) {
                HeyButton(
                    text = stringResource(R.string.admin_pause),
                    onClick = onPause,
                    kind = HeyButtonKind.Secondary,
                    enabled = !busy,
                    modifier = cell,
                )
            } else {
                HeyButton(
                    text = stringResource(R.string.admin_resume),
                    onClick = onResume,
                    kind = HeyButtonKind.Secondary,
                    enabled = !busy,
                    modifier = cell,
                )
            }
            HeyButton(
                text = stringResource(R.string.admin_delete),
                onClick = onDelete,
                kind = HeyButtonKind.Danger,
                enabled = !busy,
                modifier = cell,
            )
        }
    }
}

/** Barre d'avancement fine, lue comme telle par les lecteurs d'écran. */
@Composable
private fun ProgressBar(progress: Float) {
    val value = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(HeyTheme.colors.glassBorder)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) },
    ) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(value).background(HeyTheme.colors.primary))
    }
}

/** « En cours · 42 % · 4,2 Go · 3,1 Mo/s · 12 min ». */
@Composable
private fun downloadDetails(download: Download): String {
    val parts = mutableListOf(
        stringResource(download.state.labelRes()),
        stringResource(R.string.admin_percent, (download.progress * 100).toInt().coerceIn(0, 100)),
        bytesLabel(download.sizeBytes.toLong()),
    )
    if (download.state == DownloadState.DOWNLOADING && download.downloadSpeed > 0u) {
        parts += stringResource(R.string.admin_speed, bytesLabel(download.downloadSpeed.toLong()))
    }
    download.etaSeconds?.takeIf { download.state == DownloadState.DOWNLOADING }?.let { eta ->
        val minutes = ((eta.toLong() + 59) / 60).coerceAtLeast(1)
        parts += if (minutes < 60) {
            stringResource(R.string.admin_eta_minutes, minutes)
        } else {
            stringResource(R.string.admin_eta_hours, minutes / 60, minutes % 60)
        }
    }
    return parts.joinToString(" · ")
}
