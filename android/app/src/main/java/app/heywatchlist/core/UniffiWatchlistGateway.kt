package app.heywatchlist.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.heywatchlist_core.InternalException
import uniffi.heywatchlist_core.RequestStatus
import uniffi.heywatchlist_core.Watchlist
import uniffi.heywatchlist_core.WatchlistException
import uniffi.heywatchlist_core.WatchlistItem
import uniffi.heywatchlist_core.WatchlistSnapshot as CoreWatchlistSnapshot

/**
 * Watchlist du cœur par les liaisons UniFFI, sur un thread d'arrière-plan. Elle
 * réutilise le compte unique de [accounts]. [storePath] : fichier de la copie
 * locale et de la file d'attente hors ligne, dans un dossier privé de l'app.
 */
class UniffiWatchlistGateway(
    private val accounts: UniffiAccountGateway,
    private val storePath: String?,
    // Le cœur lit et écrit sa copie SQLite : disque, pas de calcul.
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WatchlistGateway {
    private val lock = Mutex()
    private var watchlist: Watchlist? = null

    private suspend fun watchlist(): Watchlist = lock.withLock {
        watchlist ?: Watchlist(accounts.account(), storePath).also { watchlist = it }
    }

    override suspend fun entries(): List<WatchlistEntry> = call { core -> core.items().map { it.toEntry() } }

    override suspend fun refresh(): WatchlistSnapshot = call { core -> core.refresh().toSnapshot() }

    override suspend fun add(title: Title, seasons: List<Int>?): WatchlistSnapshot = call { core ->
        core.add(title.toCore(), seasons?.map { it.coerceAtLeast(0).toUInt() }).toSnapshot()
    }

    override suspend fun remove(kind: MediaKind, id: Long): WatchlistSnapshot = call { core ->
        core.remove(kind.toCore(), id.coerceAtLeast(0L).toULong()).toSnapshot()
    }

    private suspend fun <T> call(block: suspend (Watchlist) -> T): T = withContext(dispatcher) {
        try {
            block(watchlist())
        } catch (error: CancellationException) {
            throw error
        } catch (error: WatchlistFailureException) {
            throw error
        } catch (error: AccountFailureException) {
            throw WatchlistFailureException(error.failure.toWatchlistFailure(), error)
        } catch (error: WatchlistException) {
            throw WatchlistFailureException(error.toFailure(), error)
        } catch (error: InternalException) {
            throw WatchlistFailureException(WatchlistFailure.Unknown, error)
        } catch (error: LinkageError) {
            throw WatchlistFailureException(WatchlistFailure.CoreUnavailable, error)
        }
    }
}

internal fun RequestStatus.toState(): RequestState = when (this) {
    RequestStatus.REQUESTED -> RequestState.Requested
    RequestStatus.IN_PROGRESS -> RequestState.InProgress
    RequestStatus.AVAILABLE -> RequestState.Available
    RequestStatus.REFUSED -> RequestState.Refused
}

internal fun WatchlistItem.toEntry() = WatchlistEntry(
    kind = mediaType.toKind(),
    id = tmdbId.toLong(),
    title = title,
    year = year?.toInt(),
    posterUrl = posterUrl,
    seasons = seasons?.map { it.toInt() },
    addedAt = addedAt,
    state = status.toState(),
    refusalReason = refusalReason,
    pending = pending,
)

internal fun CoreWatchlistSnapshot.toSnapshot() = WatchlistSnapshot(
    entries = items.map { it.toEntry() },
    offline = offline,
)

internal fun WatchlistException.toFailure(): WatchlistFailure = when (this) {
    is WatchlistException.SignedOut -> WatchlistFailure.SignedOut
    is WatchlistException.Rejected -> WatchlistFailure.Rejected
    is WatchlistException.Internal -> WatchlistFailure.Unknown
}

internal fun AccountFailure.toWatchlistFailure(): WatchlistFailure = when (this) {
    AccountFailure.NotConfigured -> WatchlistFailure.NotConfigured
    AccountFailure.Unavailable -> WatchlistFailure.CoreUnavailable
    AccountFailure.SignedOut -> WatchlistFailure.SignedOut
    else -> WatchlistFailure.Unknown
}
