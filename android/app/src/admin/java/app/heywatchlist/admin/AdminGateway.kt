package app.heywatchlist.admin

import androidx.annotation.StringRes
import app.heywatchlist.R
import uniffi.heywatchlist_core.AdminRequest
import uniffi.heywatchlist_core.AllowedEmail
import uniffi.heywatchlist_core.Download
import uniffi.heywatchlist_core.MediaType
import uniffi.heywatchlist_core.Member
import uniffi.heywatchlist_core.QbitSettings
import uniffi.heywatchlist_core.Release
import uniffi.heywatchlist_core.RequestFilter
import uniffi.heywatchlist_core.SearchOutcome
import uniffi.heywatchlist_core.SearchQuery
import uniffi.heywatchlist_core.ServerSettings
import uniffi.heywatchlist_core.SourceFailure
import uniffi.heywatchlist_core.SourceReport
import uniffi.heywatchlist_core.TorznabSource

/** Cause d'un échec de l'espace admin, traduite en français par ressource. */
enum class AdminFailure(@param:StringRes val messageRes: Int) {
    Network(R.string.admin_error_network),
    SignedOut(R.string.admin_error_signed_out),
    Forbidden(R.string.admin_error_forbidden),
    NotFound(R.string.admin_error_not_found),
    Conflict(R.string.admin_error_conflict),
    Server(R.string.admin_error_server),
    InvalidSettings(R.string.admin_error_invalid_settings),
    NotConfigured(R.string.admin_error_not_configured),
    QbitUnreachable(R.string.admin_error_qbit_unreachable),
    QbitAuthFailed(R.string.admin_error_qbit_auth),
    QbitBanned(R.string.admin_error_qbit_banned),
    QbitUnsupportedVersion(R.string.admin_error_qbit_version),
    QbitRejected(R.string.admin_error_qbit_rejected),
    TorrentNotFound(R.string.admin_error_torrent_not_found),
    ReleaseExpired(R.string.admin_error_release_expired),

    /** Le torrent est parti, mais la demande n'a pas pu être marquée « en cours ». */
    StatusNotSaved(R.string.admin_error_status_not_saved),
    Unavailable(R.string.error_core_unavailable),
    Unknown(R.string.error_unknown),
}

class AdminFailureException(val failure: AdminFailure, cause: Throwable? = null) : Exception(failure.name, cause)

/**
 * Frontière entre les écrans admin et l'espace admin du cœur. Chaque méthode
 * s'exécute hors du fil principal et lève [AdminFailureException]. Les types
 * sont ceux des liaisons UniFFI : de simples classes de données Kotlin.
 */
interface AdminGateway {
    /** Réglages gardés sur l'appareil (vides la première fois). */
    suspend fun settings(): ServerSettings

    /** Réglages relus sur le serveur, puis gardés sur l'appareil. */
    suspend fun syncSettings(): ServerSettings

    suspend fun saveSettings(settings: ServerSettings)

    /** `null` si la source répond. */
    suspend fun testSource(source: TorznabSource): SourceFailure?

    /** Version de qBittorrent si la connexion réussit. */
    suspend fun testQbittorrent(settings: QbitSettings): String

    suspend fun requests(filter: RequestFilter): List<AdminRequest>

    /** Recherche dans toutes les sources ; [onUpdate] reçoit les résultats au fil de l'eau. */
    suspend fun search(query: SearchQuery, onUpdate: (SearchProgress) -> Unit): SearchOutcome

    suspend fun sendRelease(requestId: String, mediaType: MediaType, releaseId: String): AdminRequest

    /** Après [AdminFailure.StatusNotSaved] : marque la demande « en cours » sans renvoyer le torrent. */
    suspend fun confirmRelease(requestId: String, releaseId: String): AdminRequest

    suspend fun refuse(requestId: String, reason: String?): AdminRequest

    suspend fun reopen(requestId: String): AdminRequest

    /** Déjà sur Plex, sans que les webhooks l'aient vue arriver en entier. */
    suspend fun markAvailable(requestId: String): AdminRequest

    suspend fun downloads(): List<Download>

    suspend fun pauseDownload(hash: String)

    suspend fun resumeDownload(hash: String)

    suspend fun deleteDownload(hash: String, deleteFiles: Boolean)

    /** Comptes, ceux en attente de validation d'abord. */
    suspend fun members(): List<Member>

    /** Accepte un compte (la personne est prévenue) ; [granted] faux : refuse ou retire l'accès. */
    suspend fun decide(memberId: String, granted: Boolean)

    suspend fun allowedEmails(): List<AllowedEmail>

    /** Nombre de comptes en attente acceptés du même coup. */
    suspend fun allowEmail(email: String): Int

    suspend fun removeAllowedEmail(email: String)
}

/** Avancement d'une recherche : tout ce qui est arrivé, trié, et le nombre de sources attendues. */
data class SearchProgress(val releases: List<Release>, val reports: List<SourceReport>, val pending: Int)
