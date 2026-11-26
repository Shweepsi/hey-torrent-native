package app.heywatchlist.core

/**
 * Watchlist simulée pour les tests : une liste en mémoire, modifiée par
 * [add] et [remove]. [failure] fait échouer l'opération suivante ; [offline]
 * marque les résultats comme hors ligne.
 */
class FakeWatchlistGateway : WatchlistGateway {
    var stored = mutableListOf<WatchlistEntry>()
    var offline = false
    var failure: WatchlistFailure? = null
    val calls = mutableListOf<String>()

    private fun snapshot() = WatchlistSnapshot(stored.toList(), offline)

    private fun failIfAsked() {
        failure?.let {
            failure = null
            throw WatchlistFailureException(it)
        }
    }

    override suspend fun entries(): List<WatchlistEntry> {
        calls += "entries"
        return stored.toList()
    }

    override suspend fun refresh(): WatchlistSnapshot {
        calls += "refresh"
        failIfAsked()
        return snapshot()
    }

    override suspend fun add(title: Title, seasons: List<Int>?): WatchlistSnapshot {
        calls += "add ${title.key} $seasons"
        failIfAsked()
        stored.removeAll { it.key == title.key }
        stored.add(0, entryOf(title.id, title.kind, seasons = seasons, pending = offline))
        return snapshot()
    }

    override suspend fun remove(kind: MediaKind, id: Long): WatchlistSnapshot {
        calls += "remove ${kind.name}-$id"
        failIfAsked()
        stored.removeAll { it.kind == kind && it.id == id }
        return snapshot()
    }
}

fun entryOf(
    id: Long,
    kind: MediaKind = MediaKind.Movie,
    state: RequestState = RequestState.Requested,
    seasons: List<Int>? = null,
    pending: Boolean = false,
    refusalReason: String? = null,
) = WatchlistEntry(
    kind = kind,
    id = id,
    title = "Titre $id",
    year = 2024,
    posterUrl = null,
    seasons = seasons,
    addedAt = 1_800_000_000L,
    state = state,
    refusalReason = refusalReason,
    pending = pending,
)
