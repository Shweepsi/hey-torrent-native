package app.heywatchlist

import androidx.compose.runtime.Composable
import app.heywatchlist.core.AccountGateway

/** Édition admin : ajoute dans Réglages les invitations et l'adresse des webhooks Plex. */
object EditionContent {
    @Composable
    fun SettingsExtras(gateway: AccountGateway, isAdmin: Boolean) {
        if (isAdmin) {
            InvitationCard(gateway)
            WebhookCard(gateway)
        }
    }
}
