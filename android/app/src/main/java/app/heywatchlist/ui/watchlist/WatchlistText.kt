package app.heywatchlist.ui.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.heywatchlist.R
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.RequestState
import app.heywatchlist.core.WatchlistEntry
import app.heywatchlist.ui.components.HeyIcons
import app.heywatchlist.ui.components.StatusPill
import app.heywatchlist.ui.components.StatusTone
import app.heywatchlist.ui.discover.pluralString

/** Nom court de l'état d'une demande. */
@Composable
internal fun stateLabel(entry: WatchlistEntry): String = when {
    entry.pending -> stringResource(R.string.watchlist_pending)
    else -> when (entry.state) {
        RequestState.Requested -> stringResource(R.string.watchlist_state_requested)
        RequestState.InProgress -> stringResource(R.string.watchlist_state_in_progress)
        RequestState.Available -> stringResource(R.string.watchlist_state_available)
        RequestState.Refused -> entry.refusalReason?.let { stringResource(R.string.watchlist_state_refused_reason, it) }
            ?: stringResource(R.string.watchlist_state_refused)
    }
}

/** Teinte de l'état : un repère en plus du texte et de l'icône, jamais seule. */
internal fun stateTone(entry: WatchlistEntry): StatusTone = when {
    entry.pending -> StatusTone.Neutral
    else -> when (entry.state) {
        RequestState.Requested -> StatusTone.Info
        RequestState.InProgress -> StatusTone.Progress
        RequestState.Available -> StatusTone.Success
        RequestState.Refused -> StatusTone.Danger
    }
}

/** Icône de l'état. */
internal fun stateIcon(entry: WatchlistEntry): ImageVector = when {
    entry.pending -> HeyIcons.Clock
    else -> when (entry.state) {
        RequestState.Requested -> HeyIcons.Clock
        RequestState.InProgress -> HeyIcons.Download
        RequestState.Available -> HeyIcons.Check
        RequestState.Refused -> HeyIcons.Close
    }
}

/** Pastille de l'état d'une demande. */
@Composable
internal fun StatePill(entry: WatchlistEntry, modifier: Modifier = Modifier) {
    StatusPill(text = stateLabel(entry), tone = stateTone(entry), icon = stateIcon(entry), modifier = modifier, maxLines = 2)
}

/** « Série entière », « Saison 2 », « Saisons 1, 3 et épisodes spéciaux ». `null` pour un film. */
@Composable
internal fun seasonsLabel(entry: WatchlistEntry): String? {
    if (entry.kind != MediaKind.Tv) return null
    val seasons = entry.seasons ?: return stringResource(R.string.watchlist_whole_series)
    val numbered = seasons.filter { it > 0 }
    val specials = 0 in seasons
    val numbers = numbered.joinToString(", ")
    return when {
        numbered.isEmpty() -> stringResource(R.string.watchlist_specials)
        specials -> pluralString(R.plurals.watchlist_seasons_and_specials, numbered.size, numbers)
        else -> pluralString(R.plurals.watchlist_seasons, numbered.size, numbers)
    }
}
