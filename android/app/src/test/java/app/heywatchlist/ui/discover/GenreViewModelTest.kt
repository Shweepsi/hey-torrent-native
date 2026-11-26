package app.heywatchlist.ui.discover

import app.heywatchlist.MainDispatcherRule
import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.FakeCatalogGateway
import app.heywatchlist.core.Genre
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.failing
import app.heywatchlist.core.pageOf
import app.heywatchlist.core.titleOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GenreViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val catalog = FakeCatalogGateway()
    private val action = Genre(28, "Action")

    private fun loaded(viewModel: GenreViewModel) = viewModel.uiState.value.list as GenreListState.Loaded

    @Test
    fun filmsOfTheGenreLoadFirst() = runTest(main.dispatcher) {
        catalog.onByGenre = { _, _, page -> pageOf(titleOf(page.toLong()), page = page, totalPages = 3) }

        val viewModel = GenreViewModel(catalog, action)
        assertEquals(GenreUiState(MediaKind.Movie, GenreListState.Loading), viewModel.uiState.value)
        advanceUntilIdle()

        assertEquals(listOf(Triple(MediaKind.Movie, 28, 1)), catalog.byGenreCalls)
        assertTrue(catalog.genreCalls.isEmpty())
        assertEquals(2, loaded(viewModel).nextPage)
    }

    @Test
    fun nextPagesAreAppendedUntilTheLastOne() = runTest(main.dispatcher) {
        catalog.onByGenre = { _, _, page -> pageOf(titleOf(page.toLong()), page = page, totalPages = 2) }
        val viewModel = GenreViewModel(catalog, action)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), loaded(viewModel).titles.map { it.id })
        assertNull(loaded(viewModel).nextPage)
        assertEquals(2, catalog.byGenreCalls.size)
    }

    @Test
    fun aFailedNextPageKeepsTheGrid() = runTest(main.dispatcher) {
        var offline = true
        catalog.onByGenre = { _, _, page ->
            if (page == 2 && offline) failing(CatalogFailure.Network)
            pageOf(titleOf(page.toLong()), page = page, totalPages = 2)
        }
        val viewModel = GenreViewModel(catalog, action)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(CatalogFailure.Network, loaded(viewModel).moreFailure)
        assertEquals(1, loaded(viewModel).titles.size)

        offline = false
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(2, loaded(viewModel).titles.size)
    }

    @Test
    fun seriesGetTheMovieGenreNumberAsIs() = runTest(main.dispatcher) {
        catalog.onByGenre = { kind, _, _ -> pageOf(titleOf(1, kind)) }
        val viewModel = GenreViewModel(catalog, action)
        advanceUntilIdle()

        viewModel.select(MediaKind.Tv)
        advanceUntilIdle()

        assertEquals(MediaKind.Tv, viewModel.uiState.value.kind)
        assertEquals(Triple(MediaKind.Tv, 28, 1), catalog.byGenreCalls.last())
        assertTrue(catalog.genreCalls.isEmpty())
        assertEquals(1, loaded(viewModel).titles.size)
    }

    @Test
    fun anEmptySeriesPageIsAnEmptyListWithNoMoreToRead() = runTest(main.dispatcher) {
        // Genre de film sans équivalent en série : le cœur répond par une page vide.
        catalog.onByGenre = { kind, _, _ ->
            if (kind == MediaKind.Tv) pageOf(page = 1, totalPages = 0) else pageOf(titleOf(1))
        }
        val viewModel = GenreViewModel(catalog, Genre(27, "Horreur"))
        advanceUntilIdle()

        viewModel.select(MediaKind.Tv)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        val list = loaded(viewModel)
        assertTrue(list.titles.isEmpty())
        assertNull(list.nextPage)
        assertEquals(2, catalog.byGenreCalls.size)
    }

    @Test
    fun failingToReadSeriesIsRetryable() = runTest(main.dispatcher) {
        var offline = true
        catalog.onByGenre = { kind, _, _ ->
            if (kind == MediaKind.Tv && offline) failing(CatalogFailure.Network)
            pageOf(titleOf(1, kind))
        }
        val viewModel = GenreViewModel(catalog, action)
        advanceUntilIdle()
        viewModel.select(MediaKind.Tv)
        advanceUntilIdle()
        assertEquals(GenreListState.Failed(CatalogFailure.Network), viewModel.uiState.value.list)

        offline = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(MediaKind.Tv, viewModel.uiState.value.kind)
        loaded(viewModel)
    }

    @Test
    fun aPageFromTheDeviceCacheIsFlaggedAndStaysFlagged() = runTest(main.dispatcher) {
        catalog.onByGenre = { _, _, page ->
            pageOf(titleOf(page.toLong()), page = page, totalPages = 2, stale = page == 1)
        }
        val viewModel = GenreViewModel(catalog, action)
        advanceUntilIdle()
        assertTrue(loaded(viewModel).stale)

        viewModel.loadMore()
        advanceUntilIdle()

        assertTrue(loaded(viewModel).stale)
        assertEquals(2, loaded(viewModel).titles.size)
    }

    @Test
    fun aFreshPageIsNotFlagged() = runTest(main.dispatcher) {
        catalog.onByGenre = { _, _, _ -> pageOf(titleOf(1)) }
        val viewModel = GenreViewModel(catalog, action)
        advanceUntilIdle()

        assertFalse(loaded(viewModel).stale)
    }

    @Test
    fun selectingTheShownKindDoesNothing() = runTest(main.dispatcher) {
        val viewModel = GenreViewModel(catalog, action)
        advanceUntilIdle()

        viewModel.select(MediaKind.Movie)
        advanceUntilIdle()

        assertEquals(1, catalog.byGenreCalls.size)
    }

    @Test
    fun anAnswerForTheKindWeLeftIsIgnored() = runTest(main.dispatcher) {
        catalog.onByGenre = { kind, _, _ -> pageOf(titleOf(1, kind)) }
        val viewModel = GenreViewModel(catalog, action)

        // Le temps de lire les films, on bascule déjà sur les séries puis on revient.
        viewModel.select(MediaKind.Tv)
        viewModel.select(MediaKind.Movie)
        advanceUntilIdle()

        assertEquals(MediaKind.Movie, viewModel.uiState.value.kind)
        assertEquals(MediaKind.Movie, loaded(viewModel).titles.single().kind)
    }
}
