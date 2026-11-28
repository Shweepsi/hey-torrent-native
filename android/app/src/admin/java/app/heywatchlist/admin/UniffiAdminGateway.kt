package app.heywatchlist.admin

import app.heywatchlist.core.AccountFailure
import app.heywatchlist.core.AccountFailureException
import app.heywatchlist.core.UniffiAccountGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.heywatchlist_core.AdminException
import uniffi.heywatchlist_core.AdminRequest
import uniffi.heywatchlist_core.AdminSpace
import uniffi.heywatchlist_core.AllowedEmail
import uniffi.heywatchlist_core.AutomationStatus
import uniffi.heywatchlist_core.Download
import uniffi.heywatchlist_core.InternalException
import uniffi.heywatchlist_core.Member
import uniffi.heywatchlist_core.PlexSettings
import uniffi.heywatchlist_core.QbitSettings
import uniffi.heywatchlist_core.Recommendation
import uniffi.heywatchlist_core.Release
import uniffi.heywatchlist_core.RequestFilter
import uniffi.heywatchlist_core.SearchObserver
import uniffi.heywatchlist_core.SearchOutcome
import uniffi.heywatchlist_core.SearchQuery
import uniffi.heywatchlist_core.ServerSettings
import uniffi.heywatchlist_core.SessionStore
import uniffi.heywatchlist_core.SourceFailure
import uniffi.heywatchlist_core.SourceReport
import uniffi.heywatchlist_core.TorznabSource

/**
 * Espace admin du cœur, sur le compte partagé du processus. [settingsStore]
 * garde une copie chiffrée des réglages des serveurs sur l'appareil.
 */
class UniffiAdminGateway(
    private val account: UniffiAccountGateway,
    private val settingsStore: SessionStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AdminGateway {
    private val lock = Mutex()
    private var space: AdminSpace? = null

    private suspend fun space(): AdminSpace {
        val current = try {
            account.account()
        } catch (error: AccountFailureException) {
            throw AdminFailureException(error.failure.toAdminFailure(), error)
        }
        return lock.withLock { space ?: call { AdminSpace(current, settingsStore) }.also { space = it } }
    }

    override suspend fun settings(): ServerSettings = space().let { call { it.settings() } }

    override suspend fun syncSettings(): ServerSettings = space().let { call { it.syncSettings() } }

    override suspend fun saveSettings(settings: ServerSettings) = space().let { call { it.saveSettings(settings) } }

    override suspend fun testSource(source: TorznabSource): SourceFailure? = space().let { call { it.testSource(source) } }

    override suspend fun testQbittorrent(settings: QbitSettings): String =
        space().let { call { it.testQbittorrent(settings) } }

    override suspend fun requests(filter: RequestFilter): List<AdminRequest> = space().let { call { it.requests(filter) } }

    override suspend fun search(query: SearchQuery, onUpdate: (SearchProgress) -> Unit): SearchOutcome {
        val observer = object : SearchObserver {
            override fun onProgress(releases: List<Release>, reports: List<SourceReport>, pending: UInt) {
                onUpdate(SearchProgress(releases, reports, pending.toInt()))
            }
        }
        return space().let { call { it.search(query, observer) } }
    }

    override suspend fun recommend(query: SearchQuery, releases: List<Release>): Recommendation =
        space().let { call { it.recommend(query, releases) } }

    override suspend fun automationStatus(): AutomationStatus = space().let { call { it.automationStatus() } }

    override suspend fun testPlex(settings: PlexSettings): Int = space().let { call { it.testPlex(settings).toInt() } }

    override suspend fun qbitCategories(): List<String> = space().let { call { it.qbitCategories() } }

    override suspend fun sendRelease(requestId: String, releaseId: String, category: String): AdminRequest =
        space().let { call { it.sendRelease(requestId, releaseId, category) } }

    override suspend fun confirmRelease(requestId: String, releaseId: String): AdminRequest =
        space().let { call { it.confirmRelease(requestId, releaseId) } }

    override suspend fun refuse(requestId: String, reason: String?): AdminRequest =
        space().let { call { it.refuse(requestId, reason) } }

    override suspend fun reopen(requestId: String): AdminRequest = space().let { call { it.reopen(requestId) } }

    override suspend fun markAvailable(requestId: String): AdminRequest =
        space().let { call { it.markAvailable(requestId) } }

    override suspend fun downloads(): List<Download> = space().let { call { it.downloads() } }

    override suspend fun pauseDownload(hash: String) = space().let { call { it.pauseDownload(hash) } }

    override suspend fun resumeDownload(hash: String) = space().let { call { it.resumeDownload(hash) } }

    override suspend fun deleteDownload(hash: String, deleteFiles: Boolean) =
        space().let { call { it.deleteDownload(hash, deleteFiles) } }

    override suspend fun members(): List<Member> = space().let { call { it.members() } }

    override suspend fun decide(memberId: String, granted: Boolean) = space().let { space ->
        call { if (granted) space.approveMember(memberId) else space.refuseMember(memberId) }
    }

    override suspend fun allowedEmails(): List<AllowedEmail> = space().let { call { it.allowedEmails() } }

    override suspend fun allowEmail(email: String): Int = space().let { call { it.allowEmail(email).toInt() } }

    override suspend fun removeAllowedEmail(email: String) = space().let { call { it.removeAllowedEmail(email) } }

    /** Exécute un appel au cœur hors du fil principal et traduit ses erreurs. */
    private suspend fun <T> call(block: suspend () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: AdminException) {
            throw AdminFailureException(error.toFailure(), error)
        } catch (error: InternalException) {
            throw AdminFailureException(AdminFailure.Unknown, error)
        } catch (error: LinkageError) {
            throw AdminFailureException(AdminFailure.Unavailable, error)
        }
    }
}

private fun AccountFailure.toAdminFailure(): AdminFailure = when (this) {
    AccountFailure.Network -> AdminFailure.Network
    AccountFailure.SignedOut, AccountFailure.InvalidIdentity -> AdminFailure.SignedOut
    AccountFailure.Server, AccountFailure.ProviderUnavailable -> AdminFailure.Server
    AccountFailure.NotConfigured -> AdminFailure.NotConfigured
    AccountFailure.Unavailable -> AdminFailure.Unavailable
    AccountFailure.Cancelled, AccountFailure.Unknown -> AdminFailure.Unknown
}

private fun AdminException.toFailure(): AdminFailure = when (this) {
    is AdminException.Network -> AdminFailure.Network
    is AdminException.SignedOut -> AdminFailure.SignedOut
    is AdminException.Forbidden -> AdminFailure.Forbidden
    is AdminException.NotFound -> AdminFailure.NotFound
    is AdminException.Conflict -> AdminFailure.Conflict
    is AdminException.Server -> AdminFailure.Server
    is AdminException.InvalidSettings -> AdminFailure.InvalidSettings
    is AdminException.NotConfigured -> AdminFailure.NotConfigured
    is AdminException.QbitUnreachable -> AdminFailure.QbitUnreachable
    is AdminException.QbitAuthFailed -> AdminFailure.QbitAuthFailed
    is AdminException.QbitBanned -> AdminFailure.QbitBanned
    is AdminException.QbitUnsupportedVersion -> AdminFailure.QbitUnsupportedVersion
    is AdminException.QbitRejected -> AdminFailure.QbitRejected
    is AdminException.TorrentNotFound -> AdminFailure.TorrentNotFound
    is AdminException.ReleaseExpired -> AdminFailure.ReleaseExpired
    is AdminException.StatusNotSaved -> AdminFailure.StatusNotSaved
    is AdminException.AssistantNotConfigured -> AdminFailure.AssistantNotConfigured
    is AdminException.AssistantAuthFailed -> AdminFailure.AssistantAuthFailed
    is AdminException.AssistantUnavailable -> AdminFailure.AssistantUnavailable
    is AdminException.PlexUnreachable -> AdminFailure.PlexUnreachable
    is AdminException.PlexAuthFailed -> AdminFailure.PlexAuthFailed
    is AdminException.Unexpected, is AdminException.Internal -> AdminFailure.Unknown
}

/** Lance une action de l'espace admin et rend son résultat ou sa cause d'échec. */
suspend fun <T> attemptAdmin(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: AdminFailureException) {
    Result.failure(error)
}

/** Cause d'un échec rendu par [attemptAdmin]. */
val Throwable.adminFailure: AdminFailure
    get() = (this as? AdminFailureException)?.failure ?: AdminFailure.Unknown
