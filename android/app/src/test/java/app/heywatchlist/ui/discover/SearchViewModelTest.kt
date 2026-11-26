package app.heywatchlist.ui.discover

import app.heywatchlist.MainDispatcherRule
import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.FakeCatalogGateway
import app.heywatchlist.core.failing
import app.heywatchlist.core.pageOf
import app.heywatchlist.core.titleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val catalog = FakeCatalogGateway()

    private fun results(viewModel: SearchViewModel) = viewModel.uiState.value.state as SearchState.Results

    @Test
    fun debounceIs350Milliseconds() {
        assertEquals(350L, SearchViewModel.DEBOUNCE_MILLIS)
    }

    @Test
    fun nothingIsReadBeforeTheDebounceEnds() = runTest(main.dispatcher) {
        val viewModel = SearchViewModel(catalog)

        viewModel.onQueryChange("matrix")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertTrue(catalog.searchCalls.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("matrix" to 1), catalog.searchCalls)
    }

    @Test
    fun onlyTheLastKeystrokeIsRead() = runTest(main.dispatcher) {
        val viewModel = SearchViewModel(catalog)

        viewModel.onQueryChange("m")
        advanceTimeBy(100)
        viewModel.onQueryChange("ma")
        advanceTimeBy(100)
        viewModel.onQueryChange("mat")
        advanceUntilIdle()

        assertEquals(listOf("mat" to 1), catalog.searchCalls)
    }

    @Test
    fun emptyQueryReadsNothingAndGoesIdle() = runTest(main.dispatcher) {
        val viewModel = SearchViewModel(catalog)

        viewModel.onQueryChange("   ")
        advanceUntilIdle()

        assertTrue(catalog.searchCalls.isEmpty())
        assertEquals(SearchState.Idle, viewModel.uiState.value.state)
        assertEquals("   ", viewModel.uiState.value.query)
    }

    @Test
    fun clearingTheQueryDropsAReadInProgress() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { _, _ ->
            withContext(NonCancellable) { gate.await() }
            pageOf(titleOf(1))
        }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("matrix")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        viewModel.onQueryChange("")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(SearchState.Idle, viewModel.uiState.value.state)
    }

    @Test
    fun anOutdatedAnswerIsIgnored() = runTest(main.dispatcher) {
        val gates = mapOf("a" to CompletableDeferred<Unit>(), "ab" to CompletableDeferred<Unit>())
        catalog.onSearch = { query, _ ->
            // Non annulable : la réponse arrive même si la lecture a été abandonnée.
            withContext(NonCancellable) { gates.getValue(query).await() }
            pageOf(titleOf(query.length.toLong()))
        }
        val viewModel = SearchViewModel(catalog)

        viewModel.onQueryChange("a")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS)
        runCurrent()
        viewModel.onQueryChange("ab")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS)
        runCurrent()

        gates.getValue("ab").complete(Unit)
        runCurrent()
        gates.getValue("a").complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, results(viewModel).titles.single().id)
    }

    @Test
    fun changingOnlyWhitespaceKeepsTheResults() = runTest(main.dispatcher) {
        catalog.onSearch = { _, _ -> pageOf(titleOf(1)) }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("matrix")
        advanceUntilIdle()

        viewModel.onQueryChange("matrix ")
        advanceUntilIdle()

        assertEquals(1, catalog.searchCalls.size)
        assertEquals("matrix ", viewModel.uiState.value.query)
        assertEquals(1, results(viewModel).titles.size)
    }

    @Test
    fun nextPageIsAppendedWithoutDuplicates() = runTest(main.dispatcher) {
        catalog.onSearch = { _, page ->
            if (page == 1) pageOf(titleOf(1), titleOf(2), page = 1, totalPages = 2)
            else pageOf(titleOf(2), titleOf(3), page = 2, totalPages = 2)
        }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("matrix")
        advanceUntilIdle()
        assertEquals(2, results(viewModel).nextPage)

        viewModel.loadMore()
        advanceUntilIdle()

        val state = results(viewModel)
        assertEquals(listOf(1L, 2L, 3L), state.titles.map { it.id })
        assertNull(state.nextPage)
        assertEquals(listOf("matrix" to 1, "matrix" to 2), catalog.searchCalls)
    }

    @Test
    fun loadMoreDoesNothingOnTheLastPageOrWhileLoading() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { _, page ->
            if (page == 2) gate.await()
            pageOf(titleOf(page.toLong()), page = page, totalPages = 2)
        }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("matrix")
        advanceUntilIdle()

        viewModel.loadMore()
        viewModel.loadMore()
        runCurrent()
        assertTrue(results(viewModel).loadingMore)
        gate.complete(Unit)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("matrix" to 1, "matrix" to 2), catalog.searchCalls)
    }

    @Test
    fun scrollingToTheEndWhileTheNextQueryWaitsDoesNotReadAPageOfTheOldResults() = runTest(main.dispatcher) {
        catalog.onSearch = { _, page -> pageOf(titleOf(page.toLong()), page = page, totalPages = 3) }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("mat")
        advanceUntilIdle()

        // Les anciens résultats restent affichés le temps de l'attente : leur fin ne lit rien.
        viewModel.onQueryChange("matr")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS - 1)
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf("mat" to 1), catalog.searchCalls)
        assertFalse(results(viewModel).loadingMore)

        advanceUntilIdle()
        assertEquals(listOf("mat" to 1, "matr" to 1), catalog.searchCalls)
        assertEquals(listOf(1L), results(viewModel).titles.map { it.id })
        assertEquals(2, results(viewModel).nextPage)
    }

    @Test
    fun aNextPageStillInFlightForThePreviousQueryIsDropped() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSearch = { query, page ->
            // Non annulable : la réponse arrive même si la lecture a été abandonnée.
            if (page == 2) withContext(NonCancellable) { gate.await() }
            pageOf(titleOf(if (query == "mat") 10L * page else 99L), page = page, totalPages = 3)
        }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("mat")
        advanceUntilIdle()
        viewModel.loadMore()
        runCurrent()

        viewModel.onQueryChange("matr")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(99L), results(viewModel).titles.map { it.id })
        assertEquals(2, results(viewModel).nextPage)
    }

    @Test
    fun aFailedNextPageKeepsTheTitlesAndCanBeRetried() = runTest(main.dispatcher) {
        var offline = true
        catalog.onSearch = { _, page ->
            if (page == 2 && offline) failing(CatalogFailure.Network)
            pageOf(titleOf(page.toLong()), page = page, totalPages = 2)
        }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("matrix")
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(CatalogFailure.Network, results(viewModel).moreFailure)
        assertEquals(1, results(viewModel).titles.size)

        offline = false
        viewModel.loadMore()
        advanceUntilIdle()

        assertNull(results(viewModel).moreFailure)
        assertEquals(2, results(viewModel).titles.size)
    }

    @Test
    fun aFailureCanBeRetriedWithoutWaiting() = runTest(main.dispatcher) {
        var offline = true
        catalog.onSearch = { _, _ -> if (offline) failing(CatalogFailure.Unavailable) else pageOf(titleOf(1)) }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("matrix")
        advanceUntilIdle()
        assertEquals(SearchState.Failed(CatalogFailure.Unavailable), viewModel.uiState.value.state)

        offline = false
        viewModel.retry()
        runCurrent()

        assertEquals(2, catalog.searchCalls.size)
        assertEquals(1, results(viewModel).titles.size)
    }

    @Test
    fun retryDoesNothingWithoutAFailure() = runTest(main.dispatcher) {
        catalog.onSearch = { _, _ -> pageOf(titleOf(1)) }
        val viewModel = SearchViewModel(catalog)
        viewModel.onQueryChange("matrix")
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(1, catalog.searchCalls.size)
    }

    @Test
    fun theQueryIsTrimmedBeforeReading() = runTest(main.dispatcher) {
        val viewModel = SearchViewModel(catalog)

        viewModel.onQueryChange("  matrix ")
        advanceUntilIdle()

        assertEquals(listOf("matrix" to 1), catalog.searchCalls)
    }
}
