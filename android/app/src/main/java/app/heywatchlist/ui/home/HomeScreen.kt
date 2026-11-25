package app.heywatchlist.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.heywatchlist.EditionContent
import app.heywatchlist.R
import app.heywatchlist.ui.components.AuroraBackground
import app.heywatchlist.ui.components.GlassSurface
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.components.LocalHazeState
import app.heywatchlist.ui.theme.HeyTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Écran d'accueil : nom de l'app, version du cœur Rust et réponse « pong ». */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(state = state, onRetry = viewModel::retry)
}

@Composable
internal fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hazeState = rememberHazeState()
    Box(modifier = modifier.fillMaxSize().background(HeyTheme.colors.background)) {
        // Le décor est la source du flou ; les surfaces en verre sont ses voisines.
        AuroraBackground(Modifier.fillMaxSize().hazeSource(hazeState))
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CoreCard(state = state, onRetry = onRetry)
                EditionContent.Footer()
            }
        }
    }
}

@Composable
private fun CoreCard(state: HomeUiState, onRetry: () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeyText(
                text = stringResource(R.string.app_name),
                style = HeyTheme.typography.display,
                modifier = Modifier.semantics { heading() },
            )
            HeyText(
                text = stringResource(R.string.home_subtitle),
                color = HeyTheme.colors.onBackgroundMuted,
            )
            // Les changements d'état sont annoncés par les lecteurs d'écran.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    HomeUiState.Loading -> HeyText(
                        text = stringResource(R.string.home_core_loading),
                        color = HeyTheme.colors.onBackgroundMuted,
                    )

                    is HomeUiState.Ready -> {
                        InfoRow(stringResource(R.string.home_core_version_label), state.info.version)
                        InfoRow(stringResource(R.string.home_core_pong_label), state.info.pong)
                    }

                    is HomeUiState.Error -> {
                        HeyText(
                            text = stringResource(state.messageRes),
                            color = HeyTheme.colors.error,
                        )
                        HeyButton(text = stringResource(R.string.home_retry), onClick = onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeyText(text = label, color = HeyTheme.colors.onBackgroundMuted)
        HeyText(text = value, style = HeyTheme.typography.title)
    }
}
