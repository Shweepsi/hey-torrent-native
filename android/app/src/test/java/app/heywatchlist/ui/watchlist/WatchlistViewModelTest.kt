package app.heywatchlist.ui.watchlist

import app.heywatchlist.MainDispatcherRule
import app.heywatchlist.core.FakeWatchlistGateway
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.RequestState
import app.heywatchlist.core.WatchlistFailure
import app.heywatchlist.core.entryOf
import app.heywatchlist.core.titleOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WatchlistViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val gateway = FakeWatchlistGateway()

    @Test
    fun openingShowsTheLocalCopyThenSynchronises() = runTest(main.dispatcher) {
        gateway.stored += entryOf(603, state = RequestState.InProgress)
        val viewModel = WatchlistViewModel(gateway)
        assertFalse(viewModel.uiState.value.loaded)

        viewModel.open()
        advanceUntilIdle()

        assertEquals(listOf("entries", "refresh"), gateway.calls)
        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertFalse(state.refreshing)
        assertEquals(RequestState.InProgress, state.entryFor(MediaKind.Movie, 603)?.state)
        assertNull(state.entryFor(MediaKind.Tv, 603))
    }

    @Test
    fun addingASeriesSendsTheChosenSeasons() = runTest(main.dispatcher) {
        val viewModel = WatchlistViewModel(gateway)
        viewModel.add(titleOf(95396, MediaKind.Tv), listOf(1, 2))
        assertTrue(watchlistKey(MediaKind.Tv, 95396) in viewModel.uiState.value.busy)
        advanceUntilIdle()

        assertEquals(listOf("add Tv-95396 [1, 2]"), gateway.calls)
        val state = viewModel.uiState.value
        assertTrue(state.busy.isEmpty())
        assertEquals(listOf(1, 2), state.entryFor(MediaKind.Tv, 95396)?.seasons)
    }

    @Test
    fun aChangeIsNotSentTwiceWhileItIsOnItsWay() = runTest(main.dispatcher) {
        val viewModel = WatchlistViewModel(gateway)
        viewModel.add(titleOf(603), null)
        viewModel.add(titleOf(603), null)
        advanceUntilIdle()
        assertEquals(1, gateway.calls.size)
    }

    @Test
    fun offlineChangesAreShownAsPending() = runTest(main.dispatcher) {
        gateway.offline = true
        val viewModel = WatchlistViewModel(gateway)
        viewModel.add(titleOf(603), null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.offline)
        assertTrue(state.entryFor(MediaKind.Movie, 603)?.pending == true)
        assertNull(state.message)
    }

    @Test
    fun aRefusedChangeShowsAMessageOnceAndRereadsTheLocalCopy() = runTest(main.dispatcher) {
        val viewModel = WatchlistViewModel(gateway)
        gateway.failure = WatchlistFailure.Rejected
        viewModel.add(titleOf(999), null)
        advanceUntilIdle()

        assertEquals(listOf("add Movie-999 null", "entries"), gateway.calls)
        assertEquals(WatchlistFailure.Rejected, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.entryFor(MediaKind.Movie, 999))
        viewModel.consumeMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun removingATitleTakesItOutOfTheList() = runTest(main.dispatcher) {
        gateway.stored += entryOf(603)
        val viewModel = WatchlistViewModel(gateway)
        viewModel.open()
        advanceUntilIdle()

        viewModel.remove(MediaKind.Movie, 603)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.isEmpty())
    }

    @Test
    fun aFailedRefreshKeepsWhatIsShown() = runTest(main.dispatcher) {
        gateway.stored += entryOf(603)
        val viewModel = WatchlistViewModel(gateway)
        gateway.failure = WatchlistFailure.Unknown
        viewModel.open()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.entries.size)
        assertFalse(state.refreshing)
        assertEquals(WatchlistFailure.Unknown, state.message)
    }
}
