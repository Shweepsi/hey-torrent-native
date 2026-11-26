package app.heywatchlist.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Title
import app.heywatchlist.core.WatchlistEntry
import app.heywatchlist.core.WatchlistFailure
import app.heywatchlist.core.WatchlistFailureException
import app.heywatchlist.core.WatchlistGateway
import app.heywatchlist.core.WatchlistSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * État de la watchlist, partagé par l'onglet Watchlist et les fiches.
 * [busy] : clés des titres dont un changement part ; [message] : échec à
 * montrer une fois, puis [WatchlistViewModel.consumeMessage].
 */
data class WatchlistUiState(
    val entries: List<WatchlistEntry> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val busy: Set<String> = emptySet(),
    val message: WatchlistFailure? = null,
) {
    fun entryFor(kind: MediaKind, id: Long): WatchlistEntry? = entries.firstOrNull { it.kind == kind && it.id == id }
}

/** Clé d'un titre, la même que [WatchlistEntry.key]. */
fun watchlistKey(kind: MediaKind, id: Long): String = "${kind.name}-$id"

/**
 * Watchlist de l'utilisateur. Vit avec l'activité : l'onglet et les fiches
 * voient la même liste. Le réseau n'est jamais un échec : le cœur met les
 * changements en attente et l'état passe [WatchlistUiState.offline].
 */
class WatchlistViewModel(private val gateway: WatchlistGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    /** Affiche la copie locale tout de suite, puis synchronise. À l'arrivée sur les onglets. */
    fun open() {
        viewModelScope.launch {
            safely { gateway.entries() }?.let { entries -> _uiState.update { it.copy(entries = entries, loaded = true) } }
            refresh()
        }
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val snapshot = safely { gateway.refresh() }
            _uiState.update { it.applied(snapshot).copy(refreshing = false) }
        }
    }

    /** [seasons] : `null` pour un film ou une série entière. */
    fun add(title: Title, seasons: List<Int>?) = change(watchlistKey(title.kind, title.id)) { gateway.add(title, seasons) }

    fun remove(kind: MediaKind, id: Long) = change(watchlistKey(kind, id)) { gateway.remove(kind, id) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private fun change(key: String, operation: suspend () -> WatchlistSnapshot) {
        if (key in _uiState.value.busy) return
        _uiState.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            val snapshot = safely(operation)
            _uiState.update { it.applied(snapshot).copy(busy = it.busy - key) }
            // Un refus laisse la copie locale à jour sans le titre : on la relit.
            if (snapshot == null) safely { gateway.entries() }?.let { entries -> _uiState.update { it.copy(entries = entries) } }
        }
    }

    private fun WatchlistUiState.applied(snapshot: WatchlistSnapshot?): WatchlistUiState =
        if (snapshot == null) this else copy(entries = snapshot.entries, loaded = true, offline = snapshot.offline)

    /** Exécute [operation] ; un échec est rangé dans [WatchlistUiState.message] et rend `null`. */
    private suspend fun <T> safely(operation: suspend () -> T): T? = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (error: WatchlistFailureException) {
        _uiState.update { it.copy(message = error.failure, loaded = true) }
        null
    } catch (error: Exception) {
        _uiState.update { it.copy(message = WatchlistFailure.Unknown, loaded = true) }
        null
    }

    companion object {
        fun factory(gateway: WatchlistGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { WatchlistViewModel(gateway) }
        }
    }
}
