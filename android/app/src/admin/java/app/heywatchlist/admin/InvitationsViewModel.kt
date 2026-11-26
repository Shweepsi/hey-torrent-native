package app.heywatchlist.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.heywatchlist_core.InvitationInfo

/** Code tout juste créé, à transmettre. */
data class CreatedInvitation(val code: String, val expiresAtSeconds: Long)

data class InvitationsUiState(
    val invitations: List<InvitationInfo> = emptyList(),
    val loading: Boolean = true,
    val failure: AdminFailure? = null,
    val creating: Boolean = false,
    val created: CreatedInvitation? = null,
    val revoking: Set<String> = emptySet(),
    val actionFailure: AdminFailure? = null,
)

/** Codes d'invitation : création, liste des derniers, révocation d'un code inutilisé. */
class InvitationsViewModel(private val gateway: AdminGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(InvitationsUiState())
    val uiState: StateFlow<InvitationsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            attemptAdmin { gateway.invitations() }
                .onSuccess { list -> _uiState.update { it.copy(invitations = list, loading = false) } }
                .onFailure { error -> _uiState.update { it.copy(loading = false, failure = error.adminFailure) } }
        }
    }

    fun create() {
        if (_uiState.value.creating) return
        _uiState.update { it.copy(creating = true, actionFailure = null) }
        viewModelScope.launch {
            attemptAdmin { gateway.createInvitation() }
                .onSuccess { (code, expiresAt) ->
                    _uiState.update { it.copy(creating = false, created = CreatedInvitation(code, expiresAt)) }
                    refresh()
                }
                .onFailure { error -> _uiState.update { it.copy(creating = false, actionFailure = error.adminFailure) } }
        }
    }

    fun revoke(invitation: InvitationInfo) {
        if (invitation.id in _uiState.value.revoking) return
        _uiState.update { it.copy(revoking = it.revoking + invitation.id, actionFailure = null) }
        viewModelScope.launch {
            val failure = attemptAdmin { gateway.revokeInvitation(invitation.id) }.exceptionOrNull()?.adminFailure
            _uiState.update { it.copy(revoking = it.revoking - invitation.id, actionFailure = failure) }
            refresh()
        }
    }

    companion object {
        fun factory(gateway: AdminGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { InvitationsViewModel(gateway) }
        }
    }
}

/** Code encore utilisable : ni utilisé, ni révoqué, ni expiré. */
fun InvitationInfo.isUsable(nowSeconds: Long): Boolean = usedAt == null && revokedAt == null && expiresAt > nowSeconds
