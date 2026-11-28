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
import app.heywatchlist.core.Access
import app.heywatchlist.core.Profile
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.discover.DetailContent
import app.heywatchlist.ui.discover.DetailUiState
import app.heywatchlist.ui.discover.DiscoverContent
import app.heywatchlist.ui.discover.DiscoverShelves
import app.heywatchlist.ui.discover.DiscoverUiState
import app.heywatchlist.ui.discover.GenresState
import app.heywatchlist.ui.discover.ShelfState
import app.heywatchlist.ui.main.Tab
import app.heywatchlist.ui.main.TabBar
import app.heywatchlist.ui.settings.SettingsContent
import app.heywatchlist.ui.watchlist.WatchlistContent
import app.heywatchlist.ui.watchlist.WatchlistUiState
import app.heywatchlist.ui.welcome.WaitingContent
import app.heywatchlist.ui.welcome.WelcomeContent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures des écrans de l'app publique, avec des données inventées. Lancées
 * seulement avec `-Pscreenshots` (job « Captures d'écran » de la CI Android).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Screenshots.PHONE, application = Application::class)
class PublicScreenshots {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun images() = Screenshots.installFakeImages()

    private val signedIn = AccountUiState.SignedIn(
        profile = Profile(firstName = "Léa", isAdmin = false, access = Access.Active),
        coreVersion = "0.0.0",
    )

    private val discover = DiscoverUiState(
        shelves = DiscoverShelves.mapIndexed { i, shelf -> shelf to ShelfState.Loaded(SampleData.titles(8, offset = i), stale = false) }
            .toMap(),
        genres = GenresState.Loaded(SampleData.genres),
    )

    @Test
    fun accueil() = Screenshots.capture(rule, "01-accueil") { WelcomeContent(AccountUiState.SignedOut(), onGoogle = {}) }

    @Test
    fun accueilSombre() = Screenshots.capture(rule, "02-accueil-sombre", dark = true) {
        WelcomeContent(AccountUiState.SignedOut(), onGoogle = {})
    }

    @Test
    fun attente() = Screenshots.capture(rule, "03-attente") {
        WaitingContent(
            state = signedIn.copy(profile = signedIn.profile.copy(access = Access.Pending)),
            onCheck = {},
            onSignOut = {},
            onDelete = {},
            onConfirmDelete = {},
            onCancelDelete = {},
        )
    }

    @Test
    fun decouvrir() = Screenshots.capture(rule, "04-decouvrir") { Tabs(Tab.Discover) { DiscoverScene() } }

    @Test
    fun decouvrirSombre() = Screenshots.capture(rule, "05-decouvrir-sombre", dark = true) { Tabs(Tab.Discover) { DiscoverScene() } }

    @Test
    fun fiche() = Screenshots.capture(rule, "06-fiche") {
        DetailContent(
            state = DetailUiState.Loaded(SampleData.seriesDetail),
            watchlist = WatchlistUiState(entries = SampleData.watchlist, loaded = true),
            onBack = {},
            onOpenTitle = {},
            onRetry = {},
            onToggleSeason = {},
            onRetrySeason = {},
            onAddToWatchlist = {},
            onRemoveFromWatchlist = {},
        )
    }

    @Test
    fun watchlist() = Screenshots.capture(rule, "07-watchlist") { Tabs(Tab.Watchlist) { WatchlistScene() } }

    @Test
    fun watchlistSombre() = Screenshots.capture(rule, "08-watchlist-sombre", dark = true) { Tabs(Tab.Watchlist) { WatchlistScene() } }

    @Test
    fun reglages() = Screenshots.capture(rule, "09-reglages") {
        Tabs(Tab.Settings) {
            SettingsContent(
                state = signedIn,
                privacyUrl = "https://exemple.invalid/confidentialite",
                onSignOut = {},
                onDelete = {},
                onConfirmDelete = {},
                onCancelDelete = {},
            )
        }
    }

    @Composable
    private fun DiscoverScene() = DiscoverContent(
        state = discover,
        onRefresh = {},
        onRetryShelf = {},
        onRetryGenres = {},
        onOpenTitle = {},
        onOpenSearch = {},
        onOpenGenre = {},
    )

    @Composable
    private fun WatchlistScene() = WatchlistContent(
        state = WatchlistUiState(entries = SampleData.watchlist, loaded = true),
        onRefresh = {},
        onOpenTitle = {},
    )

    /** Onglets comme dans `MainScreen` : l'écran, puis la barre en bas. */
    @Composable
    private fun Tabs(selected: Tab, content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            TabBar(selected = selected, onSelect = {})
        }
    }
}
