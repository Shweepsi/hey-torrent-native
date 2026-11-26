package app.heywatchlist.ui.discover

import app.heywatchlist.MainDispatcherRule
import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.FakeCatalogGateway
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.SeasonSummary
import app.heywatchlist.core.detailOf
import app.heywatchlist.core.failing
import app.heywatchlist.core.seasonOf
import app.heywatchlist.core.titleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val catalog = FakeCatalogGateway()
    private val series = titleOf(1399, MediaKind.Tv, "Une série")
    private val seasons = listOf(
        SeasonSummary(1, "Saison 1", 10, null, null),
        SeasonSummary(2, "Saison 2", 8, null, null),
    )

    private fun loaded(viewModel: DetailViewModel) = viewModel.uiState.value as DetailUiState.Loaded

    @Test
    fun theListPreviewShowsWhileTheSheetLoads() = runTest(main.dispatcher) {
        catalog.onDetail = { _, _ -> detailOf(series) }
        val viewModel = DetailViewModel(catalog, series)

        assertEquals(DetailUiState.Loading(series), viewModel.uiState.value)
        advanceUntilIdle()

        assertEquals(listOf(MediaKind.Tv to 1399L), catalog.detailCalls)
        assertEquals(series, loaded(viewModel).detail.summary)
    }

    @Test
    fun aFailureKeepsThePreviewAndCanBeRetried() = runTest(main.dispatcher) {
        var offline = true
        catalog.onDetail = { kind, id ->
            if (offline) failing(CatalogFailure.Network)
            detailOf(titleOf(id, kind))
        }
        val viewModel = DetailViewModel(catalog, series)
        advanceUntilIdle()
        assertEquals(DetailUiState.Failed(series, CatalogFailure.Network), viewModel.uiState.value)

        offline = false
        viewModel.retry()
        assertEquals(DetailUiState.Loading(series), viewModel.uiState.value)
        advanceUntilIdle()

        loaded(viewModel)
    }

    @Test
    fun retryDoesNothingOnceLoaded() = runTest(main.dispatcher) {
        val viewModel = DetailViewModel(catalog, series)
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(1, catalog.detailCalls.size)
    }

    @Test
    fun openingASeasonReadsItsEpisodesOnce() = runTest(main.dispatcher) {
        catalog.onDetail = { kind, id -> detailOf(titleOf(id, kind), seasons) }
        val viewModel = DetailViewModel(catalog, series)
        advanceUntilIdle()

        viewModel.toggleSeason(2)
        advanceUntilIdle()
        assertEquals(2, loaded(viewModel).expandedSeason)
        assertEquals(listOf(1399L to 2), catalog.seasonCalls)
        assertTrue(loaded(viewModel).seasons.getValue(2) is SeasonState.Loaded)

        viewModel.toggleSeason(2)
        assertNull(loaded(viewModel).expandedSeason)

        viewModel.toggleSeason(2)
        advanceUntilIdle()
        assertEquals(1, catalog.seasonCalls.size)
        assertEquals(2, loaded(viewModel).expandedSeason)
    }

    @Test
    fun openingAnotherSeasonClosesTheFirst() = runTest(main.dispatcher) {
        val viewModel = DetailViewModel(catalog, series)
        advanceUntilIdle()

        viewModel.toggleSeason(1)
        advanceUntilIdle()
        viewModel.toggleSeason(2)
        advanceUntilIdle()

        assertEquals(2, loaded(viewModel).expandedSeason)
        assertEquals(setOf(1, 2), loaded(viewModel).seasons.keys)
    }

    @Test
    fun aSeasonShowsLoadingWhileItIsRead() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onSeason = { _, number ->
            gate.await()
            seasonOf(number)
        }
        val viewModel = DetailViewModel(catalog, series)
        advanceUntilIdle()

        viewModel.toggleSeason(1)
        runCurrent()
        assertEquals(SeasonState.Loading, loaded(viewModel).seasons.getValue(1))

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(loaded(viewModel).seasons.getValue(1) is SeasonState.Loaded)
    }

    @Test
    fun aFailedSeasonCanBeRetried() = runTest(main.dispatcher) {
        var offline = true
        catalog.onSeason = { _, number ->
            if (offline) failing(CatalogFailure.Server)
            seasonOf(number)
        }
        val viewModel = DetailViewModel(catalog, series)
        advanceUntilIdle()
        viewModel.toggleSeason(1)
        advanceUntilIdle()
        assertEquals(SeasonState.Failed(CatalogFailure.Server), loaded(viewModel).seasons.getValue(1))

        // Refermer puis rouvrir relit aussi une saison en échec.
        offline = false
        viewModel.retrySeason(1)
        advanceUntilIdle()

        assertTrue(loaded(viewModel).seasons.getValue(1) is SeasonState.Loaded)
        assertEquals(2, catalog.seasonCalls.size)
    }

    @Test
    fun seasonsAreIgnoredBeforeTheSheetIsLoaded() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        catalog.onDetail = { kind, id ->
            gate.await()
            detailOf(titleOf(id, kind))
        }
        val viewModel = DetailViewModel(catalog, series)
        runCurrent()

        viewModel.toggleSeason(1)
        viewModel.retrySeason(1)
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(catalog.seasonCalls.isEmpty())
        assertNull(loaded(viewModel).expandedSeason)
    }
}
