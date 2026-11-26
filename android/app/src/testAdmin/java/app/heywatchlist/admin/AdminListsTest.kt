package app.heywatchlist.admin

import app.heywatchlist.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import uniffi.heywatchlist_core.DownloadState
import uniffi.heywatchlist_core.RequestFilter
import uniffi.heywatchlist_core.RequestStatus

@OptIn(ExperimentalCoroutinesApi::class)
class AdminListsTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `une demande refusée sort de la liste « à traiter »`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { requestsResult = Result.success(listOf(request("r1"), request("r2"))) }
        val model = RequestsViewModel(gateway)
        advanceUntilIdle()
        assertEquals(listOf("requests:${RequestFilter.Open}"), gateway.calls)

        model.replace(request("r1", RequestStatus.REFUSED))
        assertEquals(listOf("r2"), model.uiState.value.requests.map { it.id })
    }

    @Test
    fun `dans « toutes », une demande modifiée reste à sa place`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { requestsResult = Result.success(listOf(request("r1"), request("r2"))) }
        val model = RequestsViewModel(gateway)
        model.setFilter(RequestsFilter.All)
        advanceUntilIdle()

        model.replace(request("r1", RequestStatus.REFUSED))
        val list = model.uiState.value.requests
        assertEquals(listOf("r1", "r2"), list.map { it.id })
        assertEquals(RequestStatus.REFUSED, list.first().status)
    }

    @Test
    fun `un échec de lecture des demandes s'affiche`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { requestsResult = failure(AdminFailure.Forbidden) }
        val model = RequestsViewModel(gateway)
        advanceUntilIdle()

        assertEquals(AdminFailure.Forbidden, model.uiState.value.failure)
    }

    @Test
    fun `supprimer un téléchargement demande confirmation puis relit la liste`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { downloadsResult = Result.success(listOf(download())) }
        val model = DownloadsViewModel(gateway)
        advanceUntilIdle()
        model.askDelete(model.uiState.value.downloads.first())
        model.delete(deleteFiles = true)
        advanceUntilIdle()

        assertEquals(listOf("downloads", "delete:h1:true", "downloads"), gateway.calls)
        assertNull(model.uiState.value.deleting)
        assertEquals(emptySet<String>(), model.uiState.value.busy)
    }

    @Test
    fun `un téléchargement en pause se reprend`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { downloadsResult = Result.success(listOf(download(state = DownloadState.PAUSED))) }
        val model = DownloadsViewModel(gateway)
        advanceUntilIdle()
        val paused = model.uiState.value.downloads.first()
        assertEquals(false, paused.state.isActive())
        model.resume(paused)
        advanceUntilIdle()

        assertEquals(listOf("downloads", "resume:h1", "downloads"), gateway.calls)
    }

    @Test
    fun `une invitation créée s'affiche`() = runTest(main.dispatcher) {
        val model = InvitationsViewModel(FakeAdminGateway())
        model.create()
        advanceUntilIdle()

        assertEquals(CreatedInvitation("ABCD-EFGH", 1_800_000_000L), model.uiState.value.created)
    }
}
