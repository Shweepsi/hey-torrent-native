package app.heywatchlist.core

/**
 * Prévient [onSignedOut] quand une opération sur la watchlist échoue parce que
 * la session n'est plus valable, puis relaie l'échec tel quel (comme
 * [SessionAwareCatalogGateway]).
 */
class SessionAwareWatchlistGateway(
    private val delegate: WatchlistGateway,
    private val onSignedOut: () -> Unit,
) : WatchlistGateway {
    override suspend fun entries(): List<WatchlistEntry> = watched { delegate.entries() }

    override suspend fun refresh(): WatchlistSnapshot = watched { delegate.refresh() }

    override suspend fun add(title: Title, seasons: List<Int>?): WatchlistSnapshot = watched { delegate.add(title, seasons) }

    override suspend fun remove(kind: MediaKind, id: Long): WatchlistSnapshot = watched { delegate.remove(kind, id) }

    private suspend fun <T> watched(operation: suspend () -> T): T = try {
        operation()
    } catch (error: WatchlistFailureException) {
        if (error.failure == WatchlistFailure.SignedOut) onSignedOut()
        throw error
    }
}
