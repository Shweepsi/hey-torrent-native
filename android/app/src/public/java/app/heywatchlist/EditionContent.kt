package app.heywatchlist

import androidx.compose.runtime.Composable

/**
 * Point d'extension propre à l'édition, appelé par l'écran d'accueil. L'édition
 * publique n'ajoute rien : l'édition admin définit le même objet dans src/admin.
 */
object EditionContent {
    @Composable
    fun Footer() = Unit
}
