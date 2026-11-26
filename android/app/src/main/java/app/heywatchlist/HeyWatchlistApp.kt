package app.heywatchlist

import android.app.Application
import android.content.Context
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.KeystoreSessionStore
import app.heywatchlist.core.SessionAwareCatalogGateway
import app.heywatchlist.core.SessionAwareWatchlistGateway
import app.heywatchlist.core.UniffiAccountGateway
import app.heywatchlist.core.UniffiCatalogGateway
import app.heywatchlist.core.UniffiWatchlistGateway
import app.heywatchlist.core.WatchlistGateway
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Application : porte les services partagés par tous les écrans (voir [AppServices]). */
class HeyWatchlistApp : Application() {
    val services: AppServices by lazy { AppServices(this) }
}

/** Services du processus, créés au premier usage. */
class AppServices(context: Context) {
    /**
     * Le seul compte du cœur du processus : l'écran de connexion et le catalogue
     * y passent tous les deux (une session, un renouvellement du jeton).
     */
    val account: UniffiAccountGateway by lazy { UniffiAccountGateway(KeystoreSessionStore(context)) }

    private val _sessionExpired = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Émet quand le catalogue ou la watchlist constate que la session n'est plus valable. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired

    /** Cache hors ligne dans le dossier privé exclu de la sauvegarde. */
    val catalog: CatalogGateway by lazy {
        SessionAwareCatalogGateway(
            UniffiCatalogGateway(account, cachePath = File(context.noBackupFilesDir, CATALOG_CACHE_FILE).path),
            onSignedOut = { _sessionExpired.tryEmit(Unit) },
        )
    }

    /**
     * Watchlist : copie locale et file d'attente hors ligne, dans le même dossier
     * privé exclu de la sauvegarde. La copie appartient au compte connecté.
     */
    val watchlist: WatchlistGateway by lazy {
        SessionAwareWatchlistGateway(
            UniffiWatchlistGateway(account, storePath = File(context.noBackupFilesDir, WATCHLIST_FILE).path),
            onSignedOut = { _sessionExpired.tryEmit(Unit) },
        )
    }

    private companion object {
        const val CATALOG_CACHE_FILE = "catalog-cache.db"
        const val WATCHLIST_FILE = "watchlist.db"
    }
}

/** Services du processus, depuis n'importe quel contexte de l'app. */
val Context.appServices: AppServices
    get() = (applicationContext as HeyWatchlistApp).services
