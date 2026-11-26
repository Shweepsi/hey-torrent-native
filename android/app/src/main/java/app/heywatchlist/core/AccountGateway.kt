package app.heywatchlist.core

import androidx.annotation.StringRes
import app.heywatchlist.R

/** Ce que l'interface affiche du compte connecté. */
data class Profile(val firstName: String?, val isAdmin: Boolean)

/** Jeton d'identité obtenu de Google, à faire vérifier par le serveur. */
data class IdentityCredential(
    val idToken: String,
    /** Nonce brut donné à Google pour obtenir ce jeton. */
    val nonce: String,
    val givenName: String?,
)

/** Issue d'une tentative de connexion. */
sealed interface SignInOutcome {
    data class SignedIn(val profile: Profile) : SignInOutcome

    /** Personne inconnue du serveur : il faut un code d'invitation. */
    data object InvitationRequired : SignInOutcome
}

/**
 * Cause d'un échec, traduite en français par ressource. Indépendante des types
 * générés par UniFFI : les écrans et leurs tests JVM n'en dépendent pas.
 */
enum class AccountFailure(@param:StringRes val messageRes: Int) {
    Network(R.string.error_network),
    InvalidIdentity(R.string.error_invalid_identity),
    InvitationInvalid(R.string.error_invitation_invalid),
    SignedOut(R.string.error_signed_out),
    ProviderUnavailable(R.string.error_provider_unavailable),
    Cancelled(R.string.error_cancelled),
    Server(R.string.error_server),

    /** Adresse du serveur absente de cette compilation de l'app. */
    NotConfigured(R.string.error_not_configured),

    /** La bibliothèque native n'a pas pu être chargée. */
    Unavailable(R.string.error_core_unavailable),
    Unknown(R.string.error_unknown),
}

class AccountFailureException(val failure: AccountFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

/**
 * Frontière entre l'interface et le compte géré par le cœur Rust. Chaque
 * méthode s'exécute hors du fil principal et lève [AccountFailureException].
 */
interface AccountGateway {
    /** Version du cœur, affichée dans Réglages. */
    suspend fun coreVersion(): String

    /** Compte gardé sur l'appareil, sans réseau. */
    suspend fun currentUser(): Profile?

    /** Profil à jour depuis le serveur. */
    suspend fun refreshProfile(): Profile

    /** Client « Application Web » de Google, ou `null` s'il n'est pas configuré. */
    suspend fun googleWebClientId(): String?

    /** Nonce brut pour une nouvelle connexion. */
    suspend fun newNonce(): String

    suspend fun signIn(credential: IdentityCredential, invitationCode: String?): SignInOutcome

    /** Toujours réussie : la session locale est effacée même sans réseau. */
    suspend fun signOut()

    suspend fun deleteAccount()
}
