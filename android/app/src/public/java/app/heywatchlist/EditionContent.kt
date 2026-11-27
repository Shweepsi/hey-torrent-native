package app.heywatchlist

import androidx.compose.runtime.Composable
import app.heywatchlist.core.AccountGateway
import app.heywatchlist.core.Profile

/**
 * Points d'extension propres à l'édition. L'édition publique n'ajoute rien :
 * l'édition admin définit le même objet dans src/admin.
 */
object EditionContent {
    /** Sous la carte du compte, dans Réglages. */
    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun SettingsExtras(gateway: AccountGateway, isAdmin: Boolean) = Unit

    /** Sur l'écran d'attente de validation. */
    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun WaitingExtras(gateway: AccountGateway, onProfileChanged: (Profile) -> Unit) = Unit
}
