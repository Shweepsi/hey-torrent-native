package app.heywatchlist.core

/**
 * Prévient [onSignedOut] quand une lecture du catalogue échoue parce que la
 * session n'est plus valable (le cœur l'a déjà effacée, renouvellement du jeton
 * refusé), puis relaie l'échec tel quel : l'écran qui lisait affiche son état
 * d'erreur, mais c'est le compte qui ramène à l'accueil.
 */
class SessionAwareCatalogGateway(
    private val delegate: CatalogGateway,
    private val onSignedOut: () -> Unit,
) : CatalogGateway {
    override suspend fun shelf(shelf: Shelf, page: Int): TitlePage = watched { delegate.shelf(shelf, page) }

    override suspend fun genres(kind: MediaKind): List<Genre> = watched { delegate.genres(kind) }

    override suspend fun byGenre(kind: MediaKind, genreId: Int, page: Int): TitlePage =
        watched { delegate.byGenre(kind, genreId, page) }

    override suspend fun search(query: String, page: Int): TitlePage = watched { delegate.search(query, page) }

    override suspend fun detail(kind: MediaKind, id: Long): TitleDetail = watched { delegate.detail(kind, id) }

    override suspend fun season(tvId: Long, number: Int): SeasonDetail = watched { delegate.season(tvId, number) }

    private suspend fun <T> watched(read: suspend () -> T): T = try {
        read()
    } catch (error: CatalogFailureException) {
        if (error.failure == CatalogFailure.SignedOut) onSignedOut()
        throw error
    }
}
