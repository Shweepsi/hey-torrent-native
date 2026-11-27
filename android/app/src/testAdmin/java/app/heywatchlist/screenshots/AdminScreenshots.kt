package app.heywatchlist.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import app.heywatchlist.admin.AccessScreen
import app.heywatchlist.admin.AccessViewModel
import app.heywatchlist.admin.AdminTab
import app.heywatchlist.admin.AdminTabBar
import app.heywatchlist.admin.FakeAdminGateway
import app.heywatchlist.admin.RequestsScreen
import app.heywatchlist.admin.RequestsViewModel
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
import uniffi.heywatchlist_core.RequestFollower
import uniffi.heywatchlist_core.RequestStatus

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
