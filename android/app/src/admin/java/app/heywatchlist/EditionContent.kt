package app.heywatchlist

import androidx.compose.runtime.Composable
import app.heywatchlist.admin.AdminEntryCard
import app.heywatchlist.admin.BootstrapCard
import app.heywatchlist.core.AccountGateway
import app.heywatchlist.core.Profile

/**
 * Édition admin : ajoute dans Réglages l'entrée de l'espace admin et l'adresse
 * des webhooks Plex, et sur l'écran d'attente le code d'amorçage.
 */
object EditionContent {
    @Composable
    fun SettingsExtras(gateway: AccountGateway, isAdmin: Boolean) {
        if (isAdmin) {
            AdminEntryCard()
            WebhookCard(gateway)
        }
    }

    @Composable
    fun WaitingExtras(gateway: AccountGateway, onProfileChanged: (Profile) -> Unit) {
        BootstrapCard(gateway, onProfileChanged)
    }
}
