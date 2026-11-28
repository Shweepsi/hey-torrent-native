package app.heywatchlist.core

import app.heywatchlist.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.heywatchlist_core.AccessStatus
import uniffi.heywatchlist_core.Account
import uniffi.heywatchlist_core.AccountException
import uniffi.heywatchlist_core.IdentityProvider
import uniffi.heywatchlist_core.InternalException
import uniffi.heywatchlist_core.SessionStore
import uniffi.heywatchlist_core.SignInRequest
import uniffi.heywatchlist_core.UserProfile
import uniffi.heywatchlist_core.UserRole
import uniffi.heywatchlist_core.coreVersion as nativeCoreVersion
import uniffi.heywatchlist_core.newSignInNonce

/** Appelle le cœur par les liaisons UniFFI, sur un thread d'arrière-plan. */
class UniffiAccountGateway(
    private val store: SessionStore,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    // Le premier appel charge la bibliothèque native : disque, pas de calcul.
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountGateway {
    private val lock = Mutex()
    private var account: Account? = null

    /** Compte du cœur, créé au premier usage. Aussi utilisé par l'édition admin. */
    suspend fun account(): Account = lock.withLock {
        account ?: call {
            if (baseUrl.isBlank()) throw AccountFailureException(AccountFailure.NotConfigured)
            Account(baseUrl, store)
        }.also { account = it }
    }

    // Nom distinct : `coreVersion()` désignerait cette méthode (récursion sans fin).
    override suspend fun coreVersion(): String = call { nativeCoreVersion() }

    override suspend fun currentUser(): Profile? {
        val current = account()
        return call { current.currentUser()?.toProfile() }
    }

    override suspend fun refreshProfile(): Profile {
        val current = account()
        return call { current.refreshProfile().toProfile() }
    }

    override suspend fun googleWebClientId(): String? {
        val current = account()
        return call { current.googleClientIds().web }
    }

    override suspend fun welcomePosters(): List<String> {
        val current = account()
        return call { current.welcomePosters() }
    }

    override suspend fun newNonce(): String = call { newSignInNonce().raw }

    override suspend fun signIn(credential: IdentityCredential): Profile {
        val current = account()
        val request = SignInRequest(
            provider = IdentityProvider.GOOGLE,
            idToken = credential.idToken,
            nonce = credential.nonce,
            givenName = credential.givenName,
        )
        return call { current.signIn(request).toProfile() }
    }

    override suspend fun signOut() {
        val current = account()
        call { current.signOut() }
    }

    override suspend fun deleteAccount() {
        val current = account()
        call { current.deleteAccount(null) }
    }

    /** Exécute un appel au cœur hors du fil principal et traduit ses erreurs. */
    suspend fun <T> call(block: suspend () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: AccountFailureException) {
            throw error
        } catch (error: AccountException) {
            throw AccountFailureException(error.toFailure(), error)
        } catch (error: InternalException) {
            // Panique du cœur, rattrapée à la frontière par UniFFI.
            throw AccountFailureException(AccountFailure.Unknown, error)
        } catch (error: LinkageError) {
            // UnsatisfiedLinkError, ou NoClassDefFoundError après un premier échec.
            throw AccountFailureException(AccountFailure.Unavailable, error)
        }
    }
}

/** Profil du cœur → profil de l'interface. Aussi utilisé par l'édition admin (amorçage). */
fun UserProfile.toProfile() = Profile(
    firstName = displayName,
    isAdmin = role == UserRole.ADMIN,
    access = when (access) {
        AccessStatus.ACTIVE -> Access.Active
        AccessStatus.PENDING -> Access.Pending
        AccessStatus.REFUSED -> Access.Refused
    },
)

private fun AccountException.toFailure(): AccountFailure = when (this) {
    is AccountException.Network -> AccountFailure.Network
    is AccountException.InvalidIdentity -> AccountFailure.InvalidIdentity
    is AccountException.SignedOut -> AccountFailure.SignedOut
    is AccountException.ProviderUnavailable -> AccountFailure.ProviderUnavailable
    is AccountException.Cancelled -> AccountFailure.Cancelled
    is AccountException.Server -> AccountFailure.Server
    is AccountException.InvalidConfiguration -> AccountFailure.NotConfigured
    is AccountException.Forbidden,
    is AccountException.Unexpected,
    is AccountException.Internal,
    -> AccountFailure.Unknown
}
