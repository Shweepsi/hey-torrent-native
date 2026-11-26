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
import uniffi.heywatchlist_core.AdminRequest
import uniffi.heywatchlist_core.RequestFilter
import uniffi.heywatchlist_core.RequestStatus

/** Demandes affichées : à traiter (demandées puis en cours), ou toutes. */
enum class RequestsFilter { Open, All }

data class RequestsUiState(
    val filter: RequestsFilter = RequestsFilter.Open,
    val requests: List<AdminRequest> = emptyList(),
    val loading: Boolean = true,
    val failure: AdminFailure? = null,
)

/** Liste des demandes de tous les comptes. */
class RequestsViewModel(private val gateway: AdminGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(RequestsUiState())
    val uiState: StateFlow<RequestsUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun setFilter(filter: RequestsFilter) {
        if (_uiState.value.filter == filter) return
        _uiState.update { it.copy(filter = filter, requests = emptyList()) }
        refresh()
    }

    fun refresh() {
        job?.cancel()
        _uiState.update { it.copy(loading = true, failure = null) }
        val filter = _uiState.value.filter
        job = viewModelScope.launch {
            val wire = when (filter) {
                RequestsFilter.Open -> RequestFilter.Open
                RequestsFilter.All -> RequestFilter.All
            }
            attemptAdmin { gateway.requests(wire) }
                .onSuccess { list -> _uiState.update { it.copy(requests = list, loading = false) } }
                .onFailure { error -> _uiState.update { it.copy(loading = false, failure = error.adminFailure) } }
        }
    }

    /** Reporte dans la liste une demande modifiée depuis sa fiche. */
    fun replace(request: AdminRequest) {
        _uiState.update { state ->
            val keep = state.filter == RequestsFilter.All || request.status.isOpen()
            val list = state.requests.mapNotNull { if (it.id == request.id) request.takeIf { keep } else it }
            state.copy(requests = list)
        }
    }

    companion object {
        fun factory(gateway: AdminGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { RequestsViewModel(gateway) }
        }
    }
}

/** Demande encore à traiter : demandée, ou envoyée mais pas encore sur Plex. */
fun RequestStatus.isOpen(): Boolean = this == RequestStatus.REQUESTED || this == RequestStatus.IN_PROGRESS
