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
import uniffi.heywatchlist_core.AllowedEmail
import uniffi.heywatchlist_core.Member

data class AccessUiState(
    val members: List<Member> = emptyList(),
    val emails: List<AllowedEmail> = emptyList(),
    val loading: Boolean = true,
    val failure: AdminFailure? = null,
    /** Comptes et adresses dont une action est en cours. */
    val busy: Set<String> = emptySet(),
    val adding: Boolean = false,
    /** Après l'ajout d'une adresse : comptes en attente acceptés du même coup. */
    val approvedByEmail: Int? = null,
    val actionFailure: AdminFailure? = null,
)

/**
 * Accès des comptes : demandes en attente à accepter ou refuser, membres dont
 * on peut retirer l'accès, adresses acceptées d'avance (proches, compte de
 * démonstration pour la vérification d'Apple).
 */
class AccessViewModel(private val gateway: AdminGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(AccessUiState())
    val uiState: StateFlow<AccessUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            attemptAdmin { gateway.members() to gateway.allowedEmails() }
                .onSuccess { (members, emails) ->
                    _uiState.update { it.copy(members = members, emails = emails, loading = false) }
                }
                .onFailure { error -> _uiState.update { it.copy(loading = false, failure = error.adminFailure) } }
        }
    }

    /** Accepte ([granted]) ou refuse l'accès de ce compte. */
    fun decide(member: Member, granted: Boolean) = act(member.id) { gateway.decide(member.id, granted) }

    fun removeEmail(email: AllowedEmail) = act(email.email) { gateway.removeAllowedEmail(email.email) }

    fun addEmail(email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty() || _uiState.value.adding) return
        _uiState.update { it.copy(adding = true, actionFailure = null, approvedByEmail = null) }
        viewModelScope.launch {
            attemptAdmin { gateway.allowEmail(trimmed) }
                .onSuccess { approved -> _uiState.update { it.copy(adding = false, approvedByEmail = approved) } }
                .onFailure { error -> _uiState.update { it.copy(adding = false, actionFailure = error.adminFailure) } }
            refresh()
        }
    }

    private fun act(key: String, action: suspend () -> Unit) {
        if (key in _uiState.value.busy) return
        _uiState.update { it.copy(busy = it.busy + key, actionFailure = null) }
        viewModelScope.launch {
            val failure = attemptAdmin { action() }.exceptionOrNull()?.adminFailure
            _uiState.update { it.copy(busy = it.busy - key, actionFailure = failure) }
            refresh()
        }
    }

    companion object {
        fun factory(gateway: AdminGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccessViewModel(gateway) }
        }
    }
}
