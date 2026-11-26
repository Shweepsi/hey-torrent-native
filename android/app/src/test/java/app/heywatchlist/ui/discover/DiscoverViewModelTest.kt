package app.heywatchlist.ui.discover

import app.heywatchlist.MainDispatcherRule
import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.FakeCatalogGateway
import app.heywatchlist.core.Genre
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Shelf
import app.heywatchlist.core.failing
import app.heywatchlist.core.pageOf
import app.heywatchlist.core.titleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiscoverViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val catalog = FakeCatalogGateway()

    private fun loaded(state: DiscoverUiState, shelf: Shelf) = state.shelf(shelf) as ShelfState.Loaded

    @Test
    fun shelvesAreInTheOrderOfTheScreen() {
        assertEquals(
            listOf(
                Shelf.TrendingWeek,
                Shelf.NowPlayingMovies,
                Shelf.UpcomingMovies,
                Shelf.PopularMovies,
                Shelf.PopularSeries,
                Shelf.OnTheAirSeries,
                Shelf.TopRatedMovies,
                Shelf.TopRatedSeries,
            ),
            DiscoverShelves,
        )
        assertEquals(Shelf.entries.toSet(), DiscoverShelves.toSet())
    }

    @Test
    fun everythingLoadsOnceAtStart() = runTest(main.dispatcher) {
        catalog.onShelf = { shelf, _ -> pageOf(titleOf(shelf.ordinal.toLong())) }
        catalog.onGenres = { listOf(Genre(28, "Action")) }

        val viewModel = DiscoverViewModel(catalog)
        assertTrue(viewModel.uiState.value.shelves.values.all { it is ShelfState.Loading })
        advanceUntilIdle()

        val state = viewModel.uiState.value
        DiscoverShelves.forEach { assertEquals(1, loaded(state, it).titles.size) }
        assertEquals(GenresState.Loaded(listOf(Genre(28, "Action"))), state.genres)
        assertEquals(DiscoverShelves.map { it to 1 }.toSet(), catalog.shelfCalls.toSet())
        assertEquals(listOf(MediaKind.Movie), catalog.genreCalls)
        assertFalse(state.isStale)
    }

    @Test
    fun aFailingShelfDoesNotStopTheOthers() = runTest(main.dispatcher) {
        catalog.onShelf = { shelf, _ ->
            if (shelf == Shelf.UpcomingMovies) failing(CatalogFailure.Server) else pageOf(titleOf(1))
        }

        val viewModel = DiscoverViewModel(catalog)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ShelfState.Failed(CatalogFailure.Server), state.shelf(Shelf.UpcomingMovies))
        (DiscoverShelves - Shelf.UpcomingMovies).forEach { loaded(state, it) }
    }

    @Test
    fun anUnexpectedErrorBecomesAnUnknownFailureNotACrash() = runTest(main.dispatcher) {
        catalog.onShelf = { _, _ -> error("défaut du cœur") }

        val viewModel = DiscoverViewModel(catalog)
        advanceUntilIdle()

        assertEquals(ShelfState.Failed(CatalogFailure.Unknown), viewModel.uiState.value.shelf(Shelf.TrendingWeek))
    }

    @Test
    fun retryReadsOnlyTheFailedShelf() = runTest(main.dispatcher) {
        var failFirst = true
        catalog.onShelf = { shelf, _ ->
            if (shelf == Shelf.PopularSeries && failFirst) failing(CatalogFailure.Network) else pageOf(titleOf(1))
        }
        val viewModel = DiscoverViewModel(catalog)
        advanceUntilIdle()
        catalog.shelfCalls.clear()
        failFirst = false

        viewModel.retry(Shelf.TrendingWeek)
        viewModel.retry(Shelf.PopularSeries)
        advanceUntilIdle()

        assertEquals(listOf(Shelf.PopularSeries to 1), catalog.shelfCalls)
        loaded(viewModel.uiState.value, Shelf.PopularSeries)
    }

    @Test
    fun reloadFailedRetriesShelvesAndGenres() = runTest(main.dispatcher) {
        var offline = true
        catalog.onShelf = { _, _ -> if (offline) failing(CatalogFailure.Network) else pageOf(titleOf(1)) }
        catalog.onGenres = { if (offline) failing(CatalogFailure.Network) else listOf(Genre(1, "Drame")) }
        val viewModel = DiscoverViewModel(catalog)
        advanceUntilIdle()
        assertEquals(GenresState.Failed(CatalogFailure.Network), viewModel.uiState.value.genres)

        offline = false
        viewModel.reloadFailed()
        advanceUntilIdle()

        DiscoverShelves.forEach { loaded(viewModel.uiState.value, it) }
        assertEquals(GenresState.Loaded(listOf(Genre(1, "Drame"))), viewModel.uiState.value.genres)
    }

    @Test
    fun staleShelfRaisesTheBannerFlag() = runTest(main.dispatcher) {
        catalog.onShelf = { shelf, _ -> pageOf(titleOf(1), stale = shelf == Shelf.TopRatedSeries) }

        val viewModel = DiscoverViewModel(catalog)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isStale)
    }

    @Test
    fun duplicateTitlesInAShelfAreShownOnce() = runTest(main.dispatcher) {
        catalog.onShelf = { _, _ -> pageOf(titleOf(1), titleOf(1), titleOf(1, MediaKind.Tv)) }

        val viewModel = DiscoverViewModel(catalog)
        advanceUntilIdle()

        assertEquals(2, loaded(viewModel.uiState.value, Shelf.TrendingWeek).titles.size)
    }

    @Test
    fun refreshKeepsWhatIsShownWhileReading() = runTest(main.dispatcher) {
        val viewModel = DiscoverViewModel(catalog.apply { onShelf = { _, _ -> pageOf(titleOf(1)) } })
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        catalog.onShelf = { _, _ ->
            gate.await()
            pageOf(titleOf(2))
        }

        viewModel.refresh()
        runCurrent()

        val during = viewModel.uiState.value
        assertTrue(during.refreshing)
        assertEquals(1L, loaded(during, Shelf.TrendingWeek).titles.single().id)

        gate.complete(Unit)
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertFalse(after.refreshing)
        assertEquals(2L, loaded(after, Shelf.TrendingWeek).titles.single().id)
    }

    @Test
    fun refreshThatFailsKeepsTheOldContent() = runTest(main.dispatcher) {
        val viewModel = DiscoverViewModel(catalog.apply { onShelf = { _, _ -> pageOf(titleOf(1)) } })
        advanceUntilIdle()
        catalog.onShelf = { _, _ -> failing(CatalogFailure.Network) }

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.refreshing)
        assertEquals(1L, loaded(state, Shelf.TrendingWeek).titles.single().id)
    }

    @Test
    fun aSecondRefreshWhileOneRunsIsIgnored() = runTest(main.dispatcher) {
        val viewModel = DiscoverViewModel(catalog)
        advanceUntilIdle()
        catalog.shelfCalls.clear()
        val gate = CompletableDeferred<Unit>()
        catalog.onShelf = { _, _ ->
            gate.await()
            pageOf(titleOf(1))
        }

        viewModel.refresh()
        viewModel.refresh()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DiscoverShelves.size, catalog.shelfCalls.size)
    }
}
