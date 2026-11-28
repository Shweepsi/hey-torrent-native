package app.heywatchlist.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import app.heywatchlist.admin.AccessScreen
import app.heywatchlist.admin.AccessViewModel
import app.heywatchlist.admin.AdminTab
import app.heywatchlist.admin.AdminTabBar
import app.heywatchlist.admin.FakeAdminGateway
import app.heywatchlist.admin.RequestScreen
import app.heywatchlist.admin.RequestViewModel
import app.heywatchlist.admin.RequestsScreen
import app.heywatchlist.admin.RequestsViewModel
import app.heywatchlist.admin.ServersScreen
import app.heywatchlist.admin.ServersViewModel
import app.heywatchlist.admin.release
import app.heywatchlist.admin.request
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.heywatchlist_core.AccessStatus
import uniffi.heywatchlist_core.MediaType
import uniffi.heywatchlist_core.Member
import uniffi.heywatchlist_core.QbitAuth
import uniffi.heywatchlist_core.QbitSettings
import uniffi.heywatchlist_core.RequestFollower
import uniffi.heywatchlist_core.RequestStatus
import uniffi.heywatchlist_core.SearchOutcome
import uniffi.heywatchlist_core.ServerSettings
import uniffi.heywatchlist_core.TorznabSource

/** Captures de l'espace admin, avec des données inventées (`-Pscreenshots`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Screenshots.PHONE, application = Application::class)
class AdminScreenshots {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun images() = Screenshots.installFakeImages()

    private val gateway = FakeAdminGateway().apply {
        requestsResult = Result.success(
            listOf(
                RequestStatus.REQUESTED to listOf("Léa", "Hugo", "Inès"),
                RequestStatus.IN_PROGRESS to listOf("Malik"),
                RequestStatus.REQUESTED to listOf("Jeanne"),
                RequestStatus.AVAILABLE to listOf("Léa"),
                RequestStatus.REFUSED to listOf("Paul"),
            ).mapIndexed { i, (status, names) ->
                val title = SampleData.title(i, offset = 3)
                val series = i == 2
                request(
                    id = "r$i",
                    status = status,
                    mediaType = if (series) MediaType.TV else MediaType.MOVIE,
                    seasons = if (series) listOf(1u, 2u) else null,
                ).copy(
                    title = title.title,
                    posterUrl = title.posterUrl,
                    year = title.year?.toUInt(),
                    followers = names.map { RequestFollower(displayName = it, seasons = null, addedAt = 1_790_000_000L) },
                )
            },
        )
        membersResult = listOf(
            member("m1", "Inès", AccessStatus.PENDING),
            member("m2", "Yanis", AccessStatus.PENDING),
            member("m3", "Sam", AccessStatus.ACTIVE, admin = true),
            member("m4", "Léa", AccessStatus.ACTIVE),
            member("m5", "Hugo", AccessStatus.ACTIVE),
            member("m6", "Paul", AccessStatus.REFUSED),
        )
    }

    @Test
    fun demandes() {
        val model = RequestsViewModel(gateway)
        Screenshots.capture(rule, "20-admin-demandes") {
            AdminTabs(AdminTab.Requests) { RequestsScreen(viewModel = model, onOpen = {}) }
        }
    }

    @Test
    fun acces() {
        val model = AccessViewModel(gateway)
        Screenshots.capture(rule, "21-admin-acces") {
            AdminTabs(AdminTab.Access) { AccessScreen(viewModel = model) }
        }
    }

    @Test
    fun serveurs() {
        gateway.localSettings = ServerSettings(
            sources = listOf(TorznabSource("s1", "Ma source", "https://indexeur.invalid/1/", "cle", true)),
            qbittorrent = QbitSettings("https://qbit.invalid", QbitAuth.Bearer("jeton")),
            movieCategory = "films",
            seriesCategory = "series",
            excludedTerms = emptyList(),
        )
        gateway.remoteSettings = Result.success(gateway.localSettings)
        val model = ServersViewModel(gateway)
        Screenshots.capture(rule, "22-admin-serveurs") {
            LaunchedEffect(Unit) {
                model.testQbittorrent()
                model.testSource("s1")
            }
            AdminTabs(AdminTab.Servers) { ServersScreen(viewModel = model) }
        }
    }

    @Test
    fun releases() {
        val title = SampleData.title(0, offset = 3)
        gateway.searchResult = Result.success(
            SearchOutcome(
                listOf(
                    release("x1", "Titre.2008.MULTi.VFF.2160p.UHD.BluRay.REMUX.HDR10.TrueHD.Atmos.7.1.HEVC-GROUPE"),
                    release("x2", "Titre.2008.MULTi.VFF.2160p.BluRay.HDR.AAC.7.1.x265-GROUPE"),
                    release("x3"),
                ),
                emptyList(),
            ),
        )
        val model = RequestViewModel(
            gateway,
            request(id = "r0", status = RequestStatus.REQUESTED).copy(title = title.title, posterUrl = title.posterUrl),
        )
        Screenshots.capture(rule, "23-admin-releases") {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                RequestScreen(viewModel = model, onBack = {}, onChanged = {})
            }
        }
    }

    @Composable
    private fun AdminTabs(selected: AdminTab, content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            AdminTabBar(selected = selected, onSelect = {}, onClose = {})
        }
    }

    private fun member(id: String, name: String, access: AccessStatus, admin: Boolean = false) = Member(
        id = id,
        displayName = name,
        email = "${name.lowercase()}@example.com",
        access = access,
        isAdmin = admin,
        createdAt = 1_790_000_000L,
        statusChangedAt = if (access == AccessStatus.PENDING) null else 1_790_500_000L,
    )
}
