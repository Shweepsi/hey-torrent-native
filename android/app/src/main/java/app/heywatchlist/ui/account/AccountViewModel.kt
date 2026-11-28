package app.heywatchlist.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.HeyWatchlistApp
import app.heywatchlist.core.AccountFailure
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.AccountGateway
import app.heywatchlist.core.IdentityCredential
import app.heywatchlist.core.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Obtient un jeton Google ; fourni par l'écran, qui a l'activité sous la main. */
typealias GoogleCredentialRequester = suspend (serverClientId: String, nonce: String) -> IdentityCredential

/**
 * Connexion, attente de validation, déconnexion et suppression du compte.
 * [sessionExpired] émet quand le catalogue constate que la session n'est plus
 * valable : on revient alors à l'accueil, comme quand le renouvellement du
 * profil échoue. [accessChanged] émet quand une notification annonce que
 * l'accès a été accepté : le profil est relu.
 */
class AccountViewModel(
    val gateway: AccountGateway,
    sessionExpired: Flow<Unit> = emptyFlow(),
    accessChanged: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<AccountUiState>(AccountUiState.Starting)
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private val _welcomePosters = MutableStateFlow<List<String>>(emptyList())

    /** Affiches du décor de l'accueil et de l'attente ; vide tant qu'elles ne sont pas lues. */
    val welcomePosters: StateFlow<List<String>> = _welcomePosters.asStateFlow()
    private var postersRequested = false

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { sessionExpired.collect { returnToWelcome() } }
        viewModelScope.launch { accessChanged.collect { refresh() } }
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

    /** Met à jour le prénom et l'accès ; sans réseau, on garde ce qu'on sait. */
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

    /** La session n'est plus valable (le cœur l'a déjà effacée) : retour à l'accueil, avec l'explication. */
    private fun returnToWelcome() {
        if (_uiState.value is AccountUiState.SignedIn) {
            _uiState.value = AccountUiState.SignedOut(errorRes = AccountFailure.SignedOut.messageRes)
        }
    }

    fun signInWithGoogle(requestCredential: GoogleCredentialRequester) = runSignedOut {
        val clientId = gateway.googleWebClientId()
            ?: throw AccountFailureException(AccountFailure.ProviderUnavailable)
        val credential = requestCredential(clientId, gateway.newNonce())
        val profile = gateway.signIn(credential)
        _uiState.value = AccountUiState.SignedIn(profile, coreVersionOrEmpty())
    }

    /** Écran d'attente : la personne demande si son accès a été accepté. */
    fun checkAccess() {
        val start = _uiState.value as? AccountUiState.SignedIn ?: return
        if (start.busy || start.checking) return
        _uiState.value = start.copy(checking = true, errorRes = null)
        viewModelScope.launch {
            try {
                val profile = gateway.refreshProfile()
                _uiState.update { state ->
                    if (state is AccountUiState.SignedIn) state.copy(profile = profile, checking = false) else state
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = (error as? AccountFailureException)?.failure ?: AccountFailure.Unknown
                _uiState.value = if (failure == AccountFailure.SignedOut) {
                    AccountUiState.SignedOut(errorRes = failure.messageRes)
                } else {
                    start.copy(checking = false, errorRes = failure.messageRes)
                }
            }
        }
    }

    /** Profil changé ailleurs (code d'amorçage de l'édition admin). */
    fun profileChanged(profile: Profile) = _uiState.update { state ->
        if (state is AccountUiState.SignedIn) state.copy(profile = profile, errorRes = null) else state
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

    /**
     * Lit une fois les affiches du décor. Un échec est sans conséquence : le mur
     * dessiné reste, et la lecture sera retentée au prochain affichage.
     */
    fun loadWelcomePosters() {
        if (postersRequested || _welcomePosters.value.isNotEmpty()) return
        postersRequested = true
        viewModelScope.launch {
            try {
                _welcomePosters.value = gateway.welcomePosters()
            } catch (_: AccountFailureException) {
                postersRequested = false
            }
        }
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
                // Toujours un message, même « Connexion annulée » : sans lui, une
                // annulation qu'on n'a pas faite ressemble à un bouton sans effet.
                _uiState.value = start.copy(busy = false, errorRes = failure.messageRes)
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
                // Le compte du cœur est partagé avec le catalogue : un seul par processus.
                val services = (application as HeyWatchlistApp).services
                AccountViewModel(services.account, services.sessionExpired, services.accessChanged)
            }
        }
    }
}
