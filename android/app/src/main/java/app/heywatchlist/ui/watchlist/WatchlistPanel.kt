package app.heywatchlist.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heywatchlist.R
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.SeasonSummary
import app.heywatchlist.core.Title
import app.heywatchlist.core.WatchlistEntry
import app.heywatchlist.ui.components.HeyButton
import app.heywatchlist.ui.components.HeyButtonKind
import app.heywatchlist.ui.components.HeyIcon
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.HeyText
import app.heywatchlist.ui.theme.HeyTheme

/**
 * Ajout à la watchlist sur la fiche : un bouton tant que le titre n'y est pas,
 * puis l'état de la demande. Une série se demande entière ou par saisons.
 *
 * [seasons] : saisons de la fiche, `null` tant qu'elle se charge (le choix des
 * saisons attend). [busy] : un changement part.
 */
@Composable
fun WatchlistPanel(
    title: Title,
    entry: WatchlistEntry?,
    seasons: List<SeasonSummary>?,
    busy: Boolean,
    onAdd: (seasons: List<Int>?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable(title.key) { mutableStateOf(false) }
    val isSeries = title.kind == MediaKind.Tv
    val choices = seasons.orEmpty().distinctBy { it.number }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            picking && isSeries && choices.isNotEmpty() -> SeasonPicker(
                seasons = choices,
                initial = entry?.seasons,
                busy = busy,
                onSubmit = { chosen ->
                    picking = false
                    onAdd(chosen)
                },
                onCancel = { picking = false },
            )

            entry == null -> HeyButton(
                text = stringResource(R.string.watchlist_add),
                icon = HeyIcons.Plus,
                enabled = !busy && !(isSeries && seasons == null),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // Une série sans saison connue se demande entière.
                    if (isSeries && choices.isNotEmpty()) picking = true else onAdd(null)
                },
            )

            else -> InWatchlist(
                entry = entry,
                busy = busy,
                canEditSeasons = isSeries && choices.isNotEmpty(),
                onEditSeasons = { picking = true },
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun InWatchlist(
    entry: WatchlistEntry,
    busy: Boolean,
    canEditSeasons: Boolean,
    onEditSeasons: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeyIcon(icon = HeyIcons.Check, contentDescription = null, tint = colors.primary, iconSize = 24.dp)
            Column(
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                HeyText(text = stringResource(R.string.watchlist_in_list), style = HeyTheme.typography.label)
                StatePill(entry)
                seasonsLabel(entry)?.let {
                    HeyText(text = it, style = HeyTheme.typography.caption, color = colors.onBackgroundMuted)
                }
            }
        }
        if (canEditSeasons) {
            HeyButton(
                text = stringResource(R.string.watchlist_edit_seasons),
                kind = HeyButtonKind.Secondary,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onEditSeasons,
            )
        }
        HeyButton(
            text = stringResource(R.string.watchlist_remove),
            kind = HeyButtonKind.DangerLink,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRemove,
        )
    }
}

/**
 * Choix entre la série entière et des saisons précises. [initial] : saisons
 * déjà demandées (`null` : série entière, ou première demande).
 */
@Composable
private fun SeasonPicker(
    seasons: List<SeasonSummary>,
    initial: List<Int>?,
    busy: Boolean,
    onSubmit: (List<Int>?) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = HeyTheme.colors
    val shape = HeyTheme.shapes.card
    var whole by rememberSaveable { mutableStateOf(initial == null) }
    var chosen by rememberSaveable { mutableStateOf(initial.orEmpty().toList()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, shape)
            .padding(vertical = 8.dp),
    ) {
        HeyText(
            text = stringResource(R.string.watchlist_choose_seasons),
            style = HeyTheme.typography.title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ChoiceRow(
            label = stringResource(R.string.watchlist_whole_series),
            checked = whole,
            onToggle = {
                whole = true
                chosen = emptyList()
            },
        )
        seasons.forEach { season ->
            val label = season.name.ifBlank { stringResource(R.string.detail_season_default, season.number) }
            ChoiceRow(
                label = label,
                checked = !whole && season.number in chosen,
                onToggle = {
                    whole = false
                    chosen = if (season.number in chosen) chosen - season.number else chosen + season.number
                },
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeyButton(
                text = stringResource(R.string.watchlist_seasons_submit),
                enabled = !busy && (whole || chosen.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSubmit(if (whole) null else chosen.sorted()) },
            )
            HeyButton(
                text = stringResource(R.string.watchlist_seasons_cancel),
                kind = HeyButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel,
            )
        }
    }
}

/** Ligne à cocher, cible d'au moins 48 dp. */
@Composable
private fun ChoiceRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val colors = HeyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(HeyTheme.shapes.button)
                .background(if (checked) colors.primary else colors.glassFill)
                .border(1.dp, if (checked) colors.primary else colors.glassBorder, HeyTheme.shapes.button),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) HeyIcon(icon = HeyIcons.Check, contentDescription = null, tint = colors.onPrimary, iconSize = 18.dp)
        }
        HeyText(text = label, modifier = Modifier.weight(1f))
    }
}
