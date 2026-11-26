package app.heywatchlist.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionAwareCatalogGatewayTest {
    private val catalog = FakeCatalogGateway()
    private var signedOutCalls = 0
    private val gateway = SessionAwareCatalogGateway(catalog) { signedOutCalls++ }

    /** Ce que lève [read], ou `null` si la lecture réussit. */
    private suspend fun thrownBy(read: suspend () -> Unit): Throwable? = try {
        read()
        null
    } catch (error: Throwable) {
        error
    }

    @Test
    fun readsPassThroughWithoutNotice() = runTest {
        val page = pageOf(titleOf(1))
        catalog.onShelf = { _, _ -> page }
        catalog.onSearch = { _, _ -> page }
        catalog.onByGenre = { _, _, _ -> page }
        catalog.onGenres = { listOf(Genre(28, "Action")) }

        assertSame(page, gateway.shelf(Shelf.TrendingWeek, 1))
        assertSame(page, gateway.search("dune", 2))
        assertSame(page, gateway.byGenre(MediaKind.Movie, 28, 3))
        assertEquals(listOf(Genre(28, "Action")), gateway.genres(MediaKind.Movie))
        assertEquals(1L, gateway.detail(MediaKind.Tv, 1).summary.id)
        assertEquals(2, gateway.season(5, 2).number)

        assertEquals(listOf(Shelf.TrendingWeek to 1), catalog.shelfCalls)
        assertEquals(listOf("dune" to 2), catalog.searchCalls)
        assertEquals(listOf(Triple(MediaKind.Movie, 28, 3)), catalog.byGenreCalls)
        assertEquals(listOf(MediaKind.Tv to 1L), catalog.detailCalls)
        assertEquals(listOf(5L to 2), catalog.seasonCalls)
        assertEquals(0, signedOutCalls)
    }

    @Test
    fun anExpiredSessionIsReportedOnEveryKindOfRead() = runTest {
        catalog.onShelf = { _, _ -> failing(CatalogFailure.SignedOut) }
        catalog.onGenres = { failing(CatalogFailure.SignedOut) }
        catalog.onByGenre = { _, _, _ -> failing(CatalogFailure.SignedOut) }
        catalog.onSearch = { _, _ -> failing(CatalogFailure.SignedOut) }
        catalog.onDetail = { _, _ -> failing(CatalogFailure.SignedOut) }
        catalog.onSeason = { _, _ -> failing(CatalogFailure.SignedOut) }

        val reads: List<suspend () -> Unit> = listOf(
            { gateway.shelf(Shelf.PopularMovies, 1) },
            { gateway.genres(MediaKind.Movie) },
            { gateway.byGenre(MediaKind.Movie, 28, 1) },
            { gateway.search("dune", 1) },
            { gateway.detail(MediaKind.Movie, 1) },
            { gateway.season(1, 1) },
        )
        reads.forEach { read ->
            assertEquals(CatalogFailure.SignedOut, (thrownBy(read) as CatalogFailureException).failure)
        }

        assertEquals(reads.size, signedOutCalls)
    }

    @Test
    fun theFailureReachesTheScreenUnchanged() = runTest {
        val failure = CatalogFailureException(CatalogFailure.SignedOut)
        catalog.onShelf = { _, _ -> throw failure }

        assertSame(failure, thrownBy { gateway.shelf(Shelf.TrendingWeek, 1) })
        assertEquals(1, signedOutCalls)
    }

    @Test
    fun otherFailuresAreNotReported() = runTest {
        CatalogFailure.entries.filter { it != CatalogFailure.SignedOut }.forEach { failure ->
            catalog.onShelf = { _, _ -> failing(failure) }

            val thrown = thrownBy { gateway.shelf(Shelf.TrendingWeek, 1) } as CatalogFailureException
            assertEquals(failure, thrown.failure)
        }

        assertEquals(0, signedOutCalls)
    }

    @Test
    fun cancellationIsNotAnExpiredSession() = runTest {
        catalog.onShelf = { _, _ -> throw CancellationException("annulé") }

        assertEquals(CancellationException::class.java, thrownBy { gateway.shelf(Shelf.TrendingWeek, 1) }?.javaClass)
        assertEquals(0, signedOutCalls)
    }
}
