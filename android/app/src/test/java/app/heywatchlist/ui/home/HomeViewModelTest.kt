package app.heywatchlist.ui.home

import app.heywatchlist.R
import app.heywatchlist.core.CoreFailure
import app.heywatchlist.core.CoreFailureException
import app.heywatchlist.core.CoreGateway
import app.heywatchlist.core.CoreInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Tests de l'état de l'écran d'accueil, sans bibliothèque native (cœur simulé). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val info = CoreInfo(version = "0.1.0", pong = "pong")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeGateway(private val answer: () -> CoreInfo) : CoreGateway {
        override suspend fun load(): CoreInfo = answer()
    }

    @Test
    fun startsLoadingThenShowsTheCoreAnswer() = runTest(dispatcher) {
        val viewModel = HomeViewModel(FakeGateway { info })

        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(HomeUiState.Ready(info), viewModel.uiState.value)
    }

    @Test
    fun internalCoreErrorMapsToItsFrenchMessage() = runTest(dispatcher) {
        val viewModel = HomeViewModel(FakeGateway { throw CoreFailureException(CoreFailure.Internal) })

        advanceUntilIdle()
        assertEquals(HomeUiState.Error(R.string.error_core_internal), viewModel.uiState.value)
    }

    @Test
    fun unavailableCoreMapsToItsFrenchMessage() = runTest(dispatcher) {
        val viewModel = HomeViewModel(FakeGateway { throw CoreFailureException(CoreFailure.Unavailable) })

        advanceUntilIdle()
        assertEquals(HomeUiState.Error(R.string.error_core_unavailable), viewModel.uiState.value)
    }

    @Test
    fun unexpectedExceptionMapsToTheGenericMessage() = runTest(dispatcher) {
        val viewModel = HomeViewModel(FakeGateway { throw IllegalStateException("boom") })

        advanceUntilIdle()
        assertEquals(HomeUiState.Error(R.string.error_unknown), viewModel.uiState.value)
    }

    @Test
    fun retryRecoversAfterAFailure() = runTest(dispatcher) {
        var calls = 0
        val viewModel = HomeViewModel(
            FakeGateway {
                if (calls++ == 0) throw CoreFailureException(CoreFailure.Internal) else info
            },
        )
        advanceUntilIdle()
        assertEquals(HomeUiState.Error(R.string.error_core_internal), viewModel.uiState.value)

        viewModel.retry()
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(HomeUiState.Ready(info), viewModel.uiState.value)
    }
}
