package app.heywatchlist

import androidx.compose.runtime.Composable
import app.heywatchlist.core.AccountGateway

/** Édition admin : ajoute la création d'invitations dans Réglages. */
object EditionContent {
    @Composable
    fun SettingsExtras(gateway: AccountGateway, isAdmin: Boolean) {
        if (isAdmin) InvitationCard(gateway)
    }
}
