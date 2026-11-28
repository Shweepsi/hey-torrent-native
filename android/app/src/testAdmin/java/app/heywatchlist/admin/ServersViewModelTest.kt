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
import uniffi.heywatchlist_core.PlexSettings
import uniffi.heywatchlist_core.QbitAuth
import uniffi.heywatchlist_core.QbitSettings
import uniffi.heywatchlist_core.ServerSettings
import uniffi.heywatchlist_core.SourceFailure
import uniffi.heywatchlist_core.TorznabSource

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val remote = ServerSettings(
        sources = listOf(TorznabSource("s1", "Prowlarr", "https://indexeur.invalid/1/", "cle", true)),
        qbittorrent = QbitSettings("https://qbit.invalid", QbitAuth.Bearer("jeton")),
        movieCategory = "films",
        seriesCategory = "series",
        excludedTerms = listOf("hdcam", "dubbed"),
    )

    @Test
    fun `le formulaire rend les réglages tels quels`() {
        assertEquals(remote, ServerForm.from(remote).toSettings())
        val full = remote.copy(
            anthropicApiKey = "cle-anthropic",
            releaseRules = "MULTi VFF, 15 Go maximum",
            plex = PlexSettings("https://plex.invalid", "jeton-plex"),
        )
        assertEquals(full, ServerForm.from(full).toSettings())
    }

    @Test
    fun `clé Anthropic vide, pas de clé, et sans adresse Plex, pas de connexion`() {
        val settings = ServerForm(anthropicKey = "  ", plexUrl = " ", plexToken = "jeton").toSettings()
        assertNull(settings.anthropicApiKey)
        assertNull(settings.plex)
    }

    @Test
    fun `tester Plex, puis un changement d'adresse efface le résultat`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway()
        val model = ServersViewModel(gateway)
        advanceUntilIdle()
        model.edit { it.copy(plexUrl = "https://plex.invalid", plexToken = "jeton") }
        model.testPlex()
        advanceUntilIdle()
        assertEquals(listOf("testPlex:https://plex.invalid"), gateway.calls)
        assertEquals(ConnectionTest.Passed(), model.uiState.value.plexTest)

        model.edit { it.copy(releaseRules = "4K") }
        assertEquals(ConnectionTest.Passed(), model.uiState.value.plexTest)
        model.edit { it.copy(plexUrl = "https://autre.invalid") }
        assertNull(model.uiState.value.plexTest)
    }

    @Test
    fun `sans adresse, qBittorrent n'est pas configuré, les termes sont nettoyés`() {
        val form = ServerForm(qbitUrl = "  ", excludedTerms = " a, ,b ,", movieCategory = " ")
        val settings = form.toSettings()
        assertNull(settings.qbittorrent)
        assertEquals(listOf("a", "b"), settings.excludedTerms)
        assertEquals(ServerForm.DEFAULT_MOVIE_CATEGORY, settings.movieCategory)
    }

    @Test
    fun `à l'ouverture, la copie du serveur remplace la copie locale`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply { remoteSettings = Result.success(remote) }
        val model = ServersViewModel(gateway)
        advanceUntilIdle()

        assertEquals(remote, model.form.toSettings())
        assertEquals(false, model.uiState.value.loading)
    }

    @Test
    fun `serveur injoignable, la copie locale reste, avec l'échec`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply {
            localSettings = remote
            remoteSettings = failure(AdminFailure.Network)
        }
        val model = ServersViewModel(gateway)
        advanceUntilIdle()

        assertEquals(remote, model.form.toSettings())
        assertEquals(AdminFailure.Network, model.uiState.value.syncFailure)
    }

    @Test
    fun `ajouter une source, la tester, puis enregistrer`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway()
        val model = ServersViewModel(gateway)
        advanceUntilIdle()
        val id = model.addSource()
        assertEquals(id, model.form.sources.single().id)
        model.editSource(id) { it.copy(name = "Prowlarr", url = " https://indexeur.invalid/2/ ", apiKey = "k") }
        model.testSource(id)
        advanceUntilIdle()
        assertEquals(ConnectionTest.SourceFailed(SourceFailure.AUTH_FAILED), model.uiState.value.sourceTests[id])

        assertTrue(model.uiState.value.dirty)
        model.save()
        advanceUntilIdle()
        assertEquals("https://indexeur.invalid/2/", gateway.saved?.sources?.single()?.url)
        assertTrue(model.uiState.value.saved)
        assertEquals(false, model.uiState.value.dirty)
    }

    @Test
    fun `à l'ouverture, seuls les services réglés et pas encore testés sont testés`() = runTest(main.dispatcher) {
        val gateway = FakeAdminGateway().apply {
            localSettings = remote.copy(
                sources = remote.sources + TorznabSource("s2", "Éteinte", "https://indexeur.invalid/2/", "cle", false),
            )
            remoteSettings = Result.success(localSettings)
        }
        val model = ServersViewModel(gateway)
        advanceUntilIdle()

        model.testUntested()
        advanceUntilIdle()
        model.testUntested()
        advanceUntilIdle()

        assertEquals(listOf("testSource:s1", "testQbit"), gateway.calls)
        assertEquals(ConnectionTest.Passed("v5.0.2"), model.uiState.value.qbitTest)
    }
}
