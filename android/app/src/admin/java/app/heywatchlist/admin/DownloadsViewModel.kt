package app.heywatchlist.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.heywatchlist_core.Download

data class DownloadsUiState(
    val downloads: List<Download> = emptyList(),
    /** Première lecture, ou relecture demandée à la main. */
    val loading: Boolean = true,
    val failure: AdminFailure? = null,
    /** Téléchargements dont une action est en cours. */
    val busy: Set<String> = emptySet(),
    /** Téléchargement dont la suppression attend confirmation. */
    val deleting: Download? = null,
    val actionFailure: AdminFailure? = null,
)

/** Suivi des téléchargements de qBittorrent (catégories films et séries). */
class DownloadsViewModel(private val gateway: AdminGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    /** Relit la liste ; [quiet] : sans indicateur (relecture périodique). */
    fun refresh(quiet: Boolean = false) {
        if (quiet && job?.isActive == true) return
        job?.cancel()
        if (!quiet) _uiState.update { it.copy(loading = true, failure = null) }
        job = viewModelScope.launch {
            attemptAdmin { gateway.downloads() }
                .onSuccess { list -> _uiState.update { it.copy(downloads = list, loading = false, failure = null) } }
                .onFailure { error -> _uiState.update { it.copy(loading = false, failure = error.adminFailure) } }
        }
    }

    fun pause(download: Download) = act(download.hash) { gateway.pauseDownload(download.hash) }

    fun resume(download: Download) = act(download.hash) { gateway.resumeDownload(download.hash) }

    fun askDelete(download: Download) {
        _uiState.update { it.copy(deleting = download) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deleting = null) }
    }

    fun delete(deleteFiles: Boolean) {
        val download = _uiState.value.deleting ?: return
        _uiState.update { it.copy(deleting = null) }
        act(download.hash) { gateway.deleteDownload(download.hash, deleteFiles) }
    }

    fun dismissFailure() {
        _uiState.update { it.copy(actionFailure = null) }
    }

    private fun act(hash: String, block: suspend () -> Unit) {
        if (hash in _uiState.value.busy) return
        _uiState.update { it.copy(busy = it.busy + hash, actionFailure = null) }
        viewModelScope.launch {
            val failure = attemptAdmin { block() }.exceptionOrNull()?.adminFailure
            _uiState.update { it.copy(busy = it.busy - hash, actionFailure = failure) }
            refresh(quiet = true)
        }
    }

    companion object {
        /** Intervalle de relecture tant que l'écran est visible. */
        const val POLL_MILLIS = 5_000L

        fun factory(gateway: AdminGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { DownloadsViewModel(gateway) }
        }
    }
}
