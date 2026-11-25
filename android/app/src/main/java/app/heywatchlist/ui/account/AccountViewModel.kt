package app.heywatchlist.ui.account

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.AccountFailure
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.AccountGateway
import app.heywatchlist.core.IdentityCredential
import app.heywatchlist.core.KeystoreSessionStore
import app.heywatchlist.core.SignInOutcome
import app.heywatchlist.core.UniffiAccountGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Obtient un jeton Google ; fourni par l'écran, qui a l'activité sous la main. */
typealias GoogleCredentialRequester = suspend (serverClientId: String, nonce: String) -> IdentityCredential

/** Connexion, invitation, déconnexion et suppression du compte. */
class AccountViewModel(val gateway: AccountGateway) : ViewModel() {
    private val _uiState = MutableStateFlow<AccountUiState>(AccountUiState.Starting)
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    /** Jeton en attente d'un code d'invitation (le serveur l'accepte de nouveau). */
    private var pendingCredential: IdentityCredential? = null

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val profile = try {
            gateway.currentUser()
        } catch (error: AccountFailureException) {
            _uiState.value = AccountUiState.SignedOut(errorRes = error.failure.messageRes)
            return
        }
        if (profile == null) {
            _uiState.value = AccountUiState.SignedOut()
            return
        }
        _uiState.value = AccountUiState.SignedIn(profile, coreVersionOrEmpty())
        refresh()
    }

    /** Met à jour le prénom ; sans réseau, on garde ce qu'on sait. */
    private suspend fun refresh() {
        try {
            val profile = gateway.refreshProfile()
            _uiState.update { state -> if (state is AccountUiState.SignedIn) state.copy(profile = profile) else state }
        } catch (error: AccountFailureException) {
            if (error.failure == AccountFailure.SignedOut) {
                _uiState.value = AccountUiState.SignedOut(errorRes = error.failure.messageRes)
            }
        }
    }

    fun signInWithGoogle(requestCredential: GoogleCredentialRequester) = runSignedOut {
        val clientId = gateway.googleWebClientId()
            ?: throw AccountFailureException(AccountFailure.ProviderUnavailable)
        val credential = requestCredential(clientId, gateway.newNonce())
        pendingCredential = credential
        complete(gateway.signIn(credential, invitationCode = null))
    }

    fun submitInvitation(code: String) = runSignedOut {
        val credential = pendingCredential ?: throw AccountFailureException(AccountFailure.InvalidIdentity)
        complete(gateway.signIn(credential, code.trim()))
    }

    fun cancelInvitation() {
        pendingCredential = null
        _uiState.value = AccountUiState.SignedOut()
    }

    private suspend fun complete(outcome: SignInOutcome) {
        when (outcome) {
            SignInOutcome.InvitationRequired ->
                _uiState.value = AccountUiState.SignedOut(askInvitation = true)

            is SignInOutcome.SignedIn -> {
                pendingCredential = null
                _uiState.value = AccountUiState.SignedIn(outcome.profile, coreVersionOrEmpty())
            }
        }
    }

    fun signOut() = runSignedIn {
        gateway.signOut()
        _uiState.value = AccountUiState.SignedOut()
    }

    fun requestDeletion() = _uiState.update { state ->
        if (state is AccountUiState.SignedIn) state.copy(confirmingDeletion = true, errorRes = null) else state
    }

    fun cancelDeletion() = _uiState.update { state ->
        if (state is AccountUiState.SignedIn) state.copy(confirmingDeletion = false) else state
    }

    fun confirmDeletion() = runSignedIn {
        gateway.deleteAccount()
        _uiState.value = AccountUiState.SignedOut()
    }

    private suspend fun coreVersionOrEmpty(): String = try {
        gateway.coreVersion()
    } catch (error: AccountFailureException) {
        ""
    }

    /** Action depuis l'écran de connexion : occupé, puis erreur éventuelle. */
    private fun runSignedOut(action: suspend () -> Unit) {
        val start = _uiState.value as? AccountUiState.SignedOut ?: return
        if (start.busy) return
        _uiState.value = start.copy(busy = true, errorRes = null)
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = (error as? AccountFailureException)?.failure ?: AccountFailure.Unknown
                // Fenêtre fermée par la personne : pas de message.
                val message = failure.takeIf { it != AccountFailure.Cancelled }?.messageRes
                _uiState.value = start.copy(busy = false, errorRes = message)
            }
        }
    }

    /** Action depuis Réglages : occupé, puis erreur éventuelle. */
    private fun runSignedIn(action: suspend () -> Unit) {
        val start = _uiState.value as? AccountUiState.SignedIn ?: return
        if (start.busy) return
        _uiState.value = start.copy(busy = true, errorRes = null)
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = (error as? AccountFailureException)?.failure ?: AccountFailure.Unknown
                _uiState.value = if (failure == AccountFailure.SignedOut) {
                    AccountUiState.SignedOut(errorRes = failure.messageRes)
                } else {
                    start.copy(busy = false, confirmingDeletion = false, errorRes = failure.messageRes)
                }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                AccountViewModel(UniffiAccountGateway(KeystoreSessionStore(application as Application)))
            }
        }
    }
}
