package app.heywatchlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Échec d'une lecture du catalogue : le message, et « Réessayer ». Sans flou,
 * car elle prend place dans des listes qui défilent.
 */
@Composable
fun ErrorPanel(failure: CatalogFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.card
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeyText(
            text = stringResource(failure.messageRes),
            color = colors.onBackgroundMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        HeyButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            kind = HeyButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Bandeau discret : ce qu'on voit vient du cache de l'appareil, faute de réseau. */
@Composable
fun StaleBanner(modifier: Modifier = Modifier) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.button
    HeyText(
        text = stringResource(R.string.catalog_stale),
        style = HeyTheme.typography.caption,
        color = colors.onBackgroundMuted,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
