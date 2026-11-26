package app.heywatchlist.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.Genre
import app.heywatchlist.core.Title
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Les écrans plein écran qui s'ouvrent par-dessus l'onglet Découvrir. */
sealed interface DiscoverDestination {
    data object Search : DiscoverDestination

    /** [title] est ce qu'on sait déjà du titre : il s'affiche pendant la lecture de la fiche. */
    data class Detail(val title: Title) : DiscoverDestination

    data class GenreTitles(val genre: Genre) : DiscoverDestination
}

/**
 * Une étape de la pile. Elle porte ses propres ViewModels : ils vivent tant que
 * l'écran est dans la pile (même caché sous un autre) et disparaissent quand on
 * le quitte.
 *
 * [id] ne se répète jamais, pas même après la mort du processus : l'état
 * sauvegardé d'un écran (défilement, saisie) est rangé sous son [id], et un
 * numéro réutilisé rendrait à un nouvel écran l'état d'un ancien.
 */
class DiscoverEntry(val id: String, val destination: DiscoverDestination) : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/**
 * Pile des écrans ouverts depuis Découvrir (recherche, fiches, genre). Vide :
 * on est sur l'onglet. Elle vit autant que l'activité, donc elle survit à la
 * rotation ; l'onglet Découvrir garde ses données pendant qu'un écran est ouvert.
 */
class DiscoverNavigator : ViewModel() {
    private val _stack = MutableStateFlow<List<DiscoverEntry>>(emptyList())
    val stack: StateFlow<List<DiscoverEntry>> = _stack.asStateFlow()

    /** Ouvre un écran par-dessus les autres. Ouvrir celui qui est déjà au sommet (double toucher) ne fait rien. */
    fun open(destination: DiscoverDestination) {
        if (_stack.value.lastOrNull()?.destination == destination) return
        val entry = DiscoverEntry(UUID.randomUUID().toString(), destination)
        _stack.update { it + entry }
    }

    /** Ferme l'écran du dessus. */
    fun back() {
        val top = _stack.value.lastOrNull() ?: return
        _stack.update { it.dropLast(1) }
        top.viewModelStore.clear()
    }

    override fun onCleared() {
        _stack.value.forEach { it.viewModelStore.clear() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DiscoverNavigator() }
        }
    }
}
