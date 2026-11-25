package app.heywatchlist

import androidx.compose.runtime.Composable

/** Édition admin : ajoute l'état du code d'administration sous la carte d'accueil. */
object EditionContent {
    @Composable
    fun Footer() {
        AdminStatus()
    }
}
