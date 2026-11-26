package app.heywatchlist.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.heywatchlist_core.Catalog
import uniffi.heywatchlist_core.CatalogException
import uniffi.heywatchlist_core.InternalException

/**
 * Lit le catalogue par les liaisons UniFFI, sur un thread d'arrière-plan.
 *
 * Il n'y a qu'un compte du cœur par processus : le catalogue réutilise celui de
 * [accounts] (même session, même renouvellement du jeton). [cachePath] est le
 * fichier du cache hors ligne, dans un dossier privé de l'app ; `null` : pas de
 * cache.
 */
class UniffiCatalogGateway(
    private val accounts: UniffiAccountGateway,
    private val cachePath: String?,
    // Le cœur lit son cache SQLite : disque, pas de calcul.
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CatalogGateway {
    private val lock = Mutex()
    private var catalog: Catalog? = null

    private suspend fun catalog(): Catalog = lock.withLock {
        catalog ?: Catalog(accounts.account(), cachePath).also { catalog = it }
    }

    override suspend fun shelf(shelf: Shelf, page: Int): TitlePage =
        call { core -> core.shelf(shelf.toCore(), page.toCorePage()).toPage() }

    override suspend fun genres(kind: MediaKind): List<Genre> =
        call { core -> core.genres(kind.toCore()).map { it.toGenre() } }

    override suspend fun byGenre(kind: MediaKind, genreId: Int, page: Int): TitlePage =
        call { core -> core.byGenre(kind.toCore(), genreId.coerceAtLeast(0).toUInt(), page.toCorePage()).toPage() }

    override suspend fun search(query: String, page: Int): TitlePage =
        call { core -> core.search(query, page.toCorePage()).toPage() }

    override suspend fun detail(kind: MediaKind, id: Long): TitleDetail =
        call { core -> core.detail(kind.toCore(), id.coerceAtLeast(0L).toULong()).toDetail() }

    override suspend fun season(tvId: Long, number: Int): SeasonDetail =
        call { core ->
            core.season(tvId.coerceAtLeast(0L).toULong(), number.coerceAtLeast(0).toUInt()).toSeasonDetail()
        }

    /** Exécute un appel au cœur hors du fil principal et traduit ses erreurs. */
    private suspend fun <T> call(block: suspend (Catalog) -> T): T = withContext(dispatcher) {
        try {
            block(catalog())
        } catch (error: CancellationException) {
            throw error
        } catch (error: CatalogFailureException) {
            throw error
        } catch (error: AccountFailureException) {
            // Compte impossible à créer : adresse du serveur absente, bibliothèque illisible…
            throw CatalogFailureException(error.failure.toCatalogFailure(), error)
        } catch (error: CatalogException) {
            throw CatalogFailureException(error.toFailure(), error)
        } catch (error: InternalException) {
            // Panique du cœur, rattrapée à la frontière par UniFFI.
            throw CatalogFailureException(CatalogFailure.Unknown, error)
        } catch (error: LinkageError) {
            // UnsatisfiedLinkError, ou NoClassDefFoundError après un premier échec.
            throw CatalogFailureException(CatalogFailure.CoreUnavailable, error)
        }
    }
}

/** Numéro de page du cœur : il part de 1. */
private fun Int.toCorePage(): UInt = coerceAtLeast(1).toUInt()
