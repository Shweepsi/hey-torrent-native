package app.heywatchlist.ui.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.heywatchlist.R
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.RequestState
import app.heywatchlist.core.WatchlistEntry
import app.heywatchlist.ui.discover.pluralString
import app.heywatchlist.ui.theme.HeyTheme

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

/** Couleur de l'état : un repère en plus du texte, jamais seule. */
@Composable
internal fun stateColor(entry: WatchlistEntry): Color = when {
    entry.pending -> HeyTheme.colors.onBackgroundMuted
    entry.state == RequestState.Available -> HeyTheme.colors.primary
    entry.state == RequestState.Refused -> HeyTheme.colors.error
    else -> HeyTheme.colors.onBackground
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
