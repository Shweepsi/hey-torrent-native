package app.heywatchlist

import androidx.compose.runtime.Composable
import app.heywatchlist.admin.AdminEntryCard
import app.heywatchlist.core.AccountGateway

/** Édition admin : ajoute dans Réglages l'entrée de l'espace admin et l'adresse des webhooks Plex. */
object EditionContent {
    @Composable
    fun SettingsExtras(gateway: AccountGateway, isAdmin: Boolean) {
        if (isAdmin) {
            AdminEntryCard()
            WebhookCard(gateway)
        }
    }
}
