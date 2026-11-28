package app.heywatchlist.admin

import app.heywatchlist.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.heywatchlist_core.MediaType
import uniffi.heywatchlist_core.Recommendation
import uniffi.heywatchlist_core.RequestStatus
import uniffi.heywatchlist_core.SearchOutcome
import uniffi.heywatchlist_core.SourceReport

@OptIn(ExperimentalCoroutinesApi::class)
class RequestViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `une demande en attente lance la recherche et garde les résultats`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply {
            val partial = SearchProgress(listOf(release()), listOf(SourceReport("a", "Source A", null, 1u)), pending = 1)
            searchProgress = listOf(partial)
            searchResult = Result.success(SearchOutcome(listOf(release(), release("x2")), partial.reports))
        }
        val model = RequestViewModel(gateway, request())
        advanceUntilIdle()

        // Sans clé Anthropic, pas d'avis : la liste reste telle quelle.
        assertEquals(listOf("search:Le Voyage de Chihiro:null", "recommend:2"), gateway.calls)
        assertEquals(Advice.None, model.uiState.value.advice)
        val search = model.uiState.value.search as ReleaseSearch.Done
        assertEquals(listOf("x1", "x2"), search.releases.map { it.id })
    }

    @Test
    fun `la saison unique demandée est cherchée par défaut`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway()
        RequestViewModel(gateway, request(mediaType = MediaType.TV, seasons = listOf(2u)))
        advanceUntilIdle()

        assertEquals(listOf("search:Le Voyage de Chihiro:2"), gateway.calls)
    }

    @Test
    fun `envoyer une release confirmée met la demande à jour`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { sendResult = Result.success(request(status = RequestStatus.IN_PROGRESS)) }
        val model = RequestViewModel(gateway, request(status = RequestStatus.REFUSED))
        model.choose(release())
        advanceUntilIdle()
        model.send()
        advanceUntilIdle()

        assertEquals(listOf("categories", "send:r1:x1:films"), gateway.calls)
        val state = model.uiState.value
        assertEquals(RequestAction.Sent, state.action)
        assertEquals(RequestStatus.IN_PROGRESS, state.request.status)
        assertNull(state.confirming)
    }

    @Test
    fun `statut non enregistré, on réessaie sans renvoyer le torrent`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply {
            sendResult = failure(AdminFailure.StatusNotSaved)
            confirmResult = failure(AdminFailure.Network)
        }
        val model = RequestViewModel(gateway, request(status = RequestStatus.REFUSED))
        model.choose(release())
        advanceUntilIdle()
        model.send()
        advanceUntilIdle()
        assertEquals(RequestAction.NotSaved("x1"), model.uiState.value.action)

        model.retryStatus()
        advanceUntilIdle()
        assertEquals(RequestAction.NotSaved("x1", AdminFailure.Network), model.uiState.value.action)

        gateway.confirmResult = Result.success(request(status = RequestStatus.IN_PROGRESS))
        model.retryStatus()
        advanceUntilIdle()
        assertEquals(RequestAction.Sent, model.uiState.value.action)
        assertEquals(listOf("categories", "send:r1:x1:films", "confirm:r1:x1", "confirm:r1:x1"), gateway.calls)
    }

    @Test
    fun `refuser envoie le motif nettoyé, ou rien s'il est vide`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { refuseResult = Result.success(request(status = RequestStatus.REFUSED)) }
        val model = RequestViewModel(gateway, request(status = RequestStatus.IN_PROGRESS))
        model.openRefusal()
        model.refusalReason = "  Pas dispo en français  "
        model.refuse()
        advanceUntilIdle()
        model.refusalReason = "   "
        model.refuse()
        advanceUntilIdle()

        assertEquals(listOf("refuse:r1:Pas dispo en français", "refuse:r1:null"), gateway.calls)
        assertEquals(RequestAction.Refused, model.uiState.value.action)
        assertTrue(!model.uiState.value.refusing)
    }

    @Test
    fun `la catégorie se choisit parmi celles de qBittorrent`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { sendResult = Result.success(request(status = RequestStatus.IN_PROGRESS)) }
        val model = RequestViewModel(gateway, request(status = RequestStatus.REFUSED, mediaType = MediaType.TV))
        model.choose(release())
        advanceUntilIdle()
        assertEquals(
            CategoryChoice.Ready(listOf("Animation", "films", "series"), "series"),
            model.uiState.value.categories,
        )

        model.selectCategory("inventée")
        model.selectCategory("Animation")
        model.send()
        advanceUntilIdle()
        assertEquals(listOf("categories", "send:r1:x1:Animation"), gateway.calls)
    }

    @Test
    fun `sans catégorie dans qBittorrent, la catégorie par défaut est proposée`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { categoriesResult = Result.success(emptyList()) }
        val model = RequestViewModel(gateway, request(status = RequestStatus.REFUSED))
        model.choose(release())
        advanceUntilIdle()
        assertEquals(CategoryChoice.Ready(listOf("films"), "films"), model.uiState.value.categories)
    }

    @Test
    fun `catégories illisibles : pas d'envoi`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { categoriesResult = failure(AdminFailure.QbitUnreachable) }
        val model = RequestViewModel(gateway, request(status = RequestStatus.REFUSED))
        model.choose(release())
        advanceUntilIdle()
        model.send()
        advanceUntilIdle()

        assertEquals(CategoryChoice.Failed(AdminFailure.QbitUnreachable), model.uiState.value.categories)
        assertEquals(listOf("categories"), gateway.calls)
    }

    @Test
    fun `déjà sur Plex demande confirmation, puis se rouvre`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway()
        val model = RequestViewModel(gateway, request(status = RequestStatus.IN_PROGRESS))
        model.askMarkAvailable()
        assertTrue(model.uiState.value.confirmingAvailable)
        model.cancelMarkAvailable()
        assertTrue(!model.uiState.value.confirmingAvailable)
        assertTrue(gateway.calls.isEmpty())

        model.askMarkAvailable()
        model.markAvailable()
        advanceUntilIdle()
        assertEquals(RequestAction.MarkedAvailable, model.uiState.value.action)
        assertEquals(RequestStatus.AVAILABLE, model.uiState.value.request.status)
        assertTrue(!model.uiState.value.confirmingAvailable)

        model.reopen()
        advanceUntilIdle()
        assertEquals(listOf("available:r1", "reopen:r1"), gateway.calls)
        assertEquals(RequestStatus.REQUESTED, model.uiState.value.request.status)
    }

    @Test
    fun `un échec de recherche s'affiche`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { searchResult = failure(AdminFailure.NotConfigured) }
        val model = RequestViewModel(gateway, request())
        advanceUntilIdle()

        assertEquals(ReleaseSearch.Failed(AdminFailure.NotConfigured), model.uiState.value.search)
    }

    @Test
    fun `Claude conseille une release après la recherche`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply {
            searchResult = Result.success(SearchOutcome(listOf(release(), release("x2", "Autre.2001.VOSTFR")), emptyList()))
            recommendResult = Result.success(Recommendation("x2", "Seule version complète."))
        }
        val model = RequestViewModel(gateway, request())
        advanceUntilIdle()

        assertEquals(Advice.Ready("x2", "Seule version complète."), model.uiState.value.advice)
    }

    @Test
    fun `la release trouvée par la recherche automatique est reprise sans rappeler Claude`() = runTest(main.dispatcher) {
        val found = release("x2", "Le.Voyage.de.Chihiro.2001.MULTi.2160p")
        val gateway = FakeAdminGateway().apply { searchResult = Result.success(SearchOutcome(listOf(release(), found), emptyList())) }
        val model = RequestViewModel(gateway, request(candidateTitle = found.title, candidateReason = "4K MULTi."))
        advanceUntilIdle()

        assertEquals(listOf("search:Le Voyage de Chihiro:null"), gateway.calls)
        assertEquals(Advice.Ready("x2", "4K MULTi.", automatic = true), model.uiState.value.advice)
    }

    @Test
    fun `un avis impossible s'affiche, sans bloquer la liste`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply {
            searchResult = Result.success(SearchOutcome(listOf(release()), emptyList()))
            recommendResult = failure(AdminFailure.AssistantAuthFailed)
        }
        val model = RequestViewModel(gateway, request())
        advanceUntilIdle()

        assertEquals(Advice.Failed(AdminFailure.AssistantAuthFailed), model.uiState.value.advice)
        assertTrue(model.uiState.value.search is ReleaseSearch.Done)
    }
}
