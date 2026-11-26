package app.heywatchlist.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAwareWatchlistGatewayTest {
    private val watchlist = FakeWatchlistGateway()
    private var signedOutCalls = 0
    private val gateway = SessionAwareWatchlistGateway(watchlist) { signedOutCalls++ }

    private suspend fun failureOf(operation: suspend () -> Unit): WatchlistFailure? = try {
        operation()
        null
    } catch (error: WatchlistFailureException) {
        error.failure
    }

    @Test
    fun operationsPassThroughWithoutNotice() = runTest {
        gateway.add(titleOf(1), null)
        assertEquals(1, gateway.refresh().entries.size)
        gateway.remove(MediaKind.Movie, 1)
        assertEquals(0, gateway.entries().size)
        assertEquals(0, signedOutCalls)
    }

    @Test
    fun anExpiredSessionIsReportedThenRethrown() = runTest {
        watchlist.failure = WatchlistFailure.SignedOut
        assertEquals(WatchlistFailure.SignedOut, failureOf { gateway.refresh() })
        assertEquals(1, signedOutCalls)

        watchlist.failure = WatchlistFailure.Rejected
        assertEquals(WatchlistFailure.Rejected, failureOf { gateway.add(titleOf(2), null) })
        assertEquals(1, signedOutCalls)
    }
}
