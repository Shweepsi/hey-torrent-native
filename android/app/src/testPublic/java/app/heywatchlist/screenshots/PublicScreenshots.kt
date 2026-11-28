package app.heywatchlist.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.heywatchlist.core.Access
import app.heywatchlist.core.Profile
import app.heywatchlist.core.WatchlistEntry
import app.heywatchlist.ui.account.AccountUiState
import app.heywatchlist.ui.discover.DetailContent
import app.heywatchlist.ui.discover.DetailUiState
import app.heywatchlist.ui.discover.DiscoverContent
import app.heywatchlist.ui.discover.DiscoverShelves
import app.heywatchlist.ui.discover.DiscoverUiState
import app.heywatchlist.ui.discover.GenresState
import app.heywatchlist.ui.discover.SearchContent
import app.heywatchlist.ui.discover.SearchState
import app.heywatchlist.ui.discover.SearchUiState
import app.heywatchlist.ui.discover.ShelfState
import app.heywatchlist.ui.main.Tab
import app.heywatchlist.ui.main.TabsScaffold
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
    fun accueil() = Screenshots.capture(rule, "01-accueil") {
        WelcomeContent(AccountUiState.SignedOut(), onGoogle = {}, posters = posters)
    }

    @Test
    fun accueilSombre() = Screenshots.capture(rule, "02-accueil-sombre", dark = true) {
        WelcomeContent(AccountUiState.SignedOut(), onGoogle = {}, posters = posters)
    }

    /** Sans affiches (pas de réseau) : le mur dessiné. */
    @Test
    fun accueilSansReseau() = Screenshots.capture(rule, "02b-accueil-sans-reseau") {
        WelcomeContent(AccountUiState.SignedOut(), onGoogle = {})
    }

    private val posters = SampleData.titles(16).mapNotNull { it.posterUrl }

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

    // Contenu défilé : les barres flottantes floutent ce qui passe dessous.
    @Test
    fun decouvrirDefile() = Screenshots.capture(rule, "05b-decouvrir-defile", act = { scrollDown() }) {
        Tabs(Tab.Discover) { DiscoverScene() }
    }

    @Test
    fun decouvrirDefileSombre() = Screenshots.capture(rule, "05c-decouvrir-defile-sombre", dark = true, act = { scrollDown() }) {
        Tabs(Tab.Discover) { DiscoverScene() }
    }

    @Test
    fun fiche() = Screenshots.capture(rule, "06-fiche") { DetailScene() }

    @Test
    fun ficheSombre() = Screenshots.capture(rule, "06b-fiche-sombre", dark = true) { DetailScene() }

    @Test
    fun ficheDefilee() = Screenshots.capture(rule, "06c-fiche-defilee", act = { scrollDown() }) { DetailScene() }

    // Titre pas encore dans la watchlist : le bouton principal « Ajouter à ma watchlist ».
    @Test
    fun ficheAAjouter() = Screenshots.capture(rule, "06d-fiche-a-ajouter") { DetailScene(entries = emptyList()) }

    @Test
    fun ficheAAjouterSombre() = Screenshots.capture(rule, "06e-fiche-a-ajouter-sombre", dark = true) {
        DetailScene(entries = emptyList())
    }

    @Composable
    private fun DetailScene(entries: List<WatchlistEntry> = SampleData.watchlist) {
        DetailContent(
            state = DetailUiState.Loaded(SampleData.seriesDetail),
            watchlist = WatchlistUiState(entries = entries, loaded = true),
            onBack = {},
            onOpenTitle = {},
            onRetry = {},
            onToggleSeason = {},
            onRetrySeason = {},
            onAddToWatchlist = {},
            onRemoveFromWatchlist = {},
        )
    }

    // En-tête flottant de la recherche, résultats défilés dessous.
    @Test
    fun recherche() = Screenshots.capture(rule, "10-recherche", act = { scrollDown() }) { SearchScene() }

    @Test
    fun rechercheSombre() = Screenshots.capture(rule, "10b-recherche-sombre", dark = true) { SearchScene() }

    @Composable
    private fun SearchScene() = SearchContent(
        text = "la",
        onTextChange = {},
        state = SearchUiState(query = "la", state = SearchState.Results(SampleData.titles(12), nextPage = null)),
        onBack = {},
        onRetry = {},
        onLoadMore = {},
        onOpenTitle = {},
    )

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

    /** Onglets comme dans `MainScreen` : l'écran bord à bord, la barre flottante par-dessus. */
    @Composable
    private fun Tabs(selected: Tab, content: @Composable () -> Unit) {
        TabsScaffold(selected = selected, onSelect = {}) { content() }
    }

    /** Un défilement d'environ un écran vers le bas. */
    private fun ComposeContentTestRule.scrollDown() {
        onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.2f) }
    }
}
