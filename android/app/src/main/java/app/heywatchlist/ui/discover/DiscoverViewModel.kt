package app.heywatchlist.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.CatalogResult
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Shelf
import app.heywatchlist.core.attempt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Écran Découvrir : une page de chaque liste du catalogue et les genres de
 * films. Chaque rangée charge à part : l'échec de l'une n'empêche pas les
 * autres. Il vit autant que l'activité, donc les données survivent au
 * changement d'onglet et à la rotation.
 */
class DiscoverViewModel(private val catalog: CatalogGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private val shelfJobs = mutableMapOf<Shelf, Job>()
    private var genresJob: Job? = null

    init {
        DiscoverShelves.forEach { loadShelf(it) }
        loadGenres()
    }

    /** Relance une rangée en échec. */
    fun retry(shelf: Shelf) {
        if (_uiState.value.shelf(shelf) is ShelfState.Failed) loadShelf(shelf)
    }

    fun retryGenres() {
        if (_uiState.value.genres is GenresState.Failed) loadGenres()
    }

    /** Relance tout ce qui avait échoué (retour sur l'écran après une reconnexion, par exemple). */
    fun reloadFailed() {
        DiscoverShelves.forEach { retry(it) }
        retryGenres()
    }

    /**
     * « Tirer pour rafraîchir » : tout est relu, et ce qui est affiché reste en
     * place pendant la lecture (et si elle échoue).
     */
    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true) }
        val jobs = DiscoverShelves.map { loadShelf(it) } + loadGenres()
        viewModelScope.launch {
            jobs.joinAll()
            _uiState.update { it.copy(refreshing = false) }
        }
    }

    private fun loadShelf(shelf: Shelf): Job {
        shelfJobs[shelf]?.cancel()
        val previous = _uiState.value.shelf(shelf)
        if (previous !is ShelfState.Loaded) setShelf(shelf, ShelfState.Loading)
        val job = viewModelScope.launch {
            val next = when (val result = attempt { catalog.shelf(shelf, FIRST_PAGE) }) {
                is CatalogResult.Success ->
                    ShelfState.Loaded(result.value.items.distinctBy { it.key }, result.value.stale)

                // Un échec ne remplace pas ce qui est déjà affiché.
                is CatalogResult.Failure ->
                    if (previous is ShelfState.Loaded) previous else ShelfState.Failed(result.failure)
            }
            setShelf(shelf, next)
        }
        shelfJobs[shelf] = job
        return job
    }

    private fun setShelf(shelf: Shelf, state: ShelfState) {
        _uiState.update { it.copy(shelves = it.shelves + (shelf to state)) }
    }

    private fun loadGenres(): Job {
        genresJob?.cancel()
        val previous = _uiState.value.genres
        if (previous !is GenresState.Loaded) _uiState.update { it.copy(genres = GenresState.Loading) }
        val job = viewModelScope.launch {
            val next = when (val result = attempt { catalog.genres(MediaKind.Movie) }) {
                is CatalogResult.Success -> GenresState.Loaded(result.value.distinctBy { it.id })
                is CatalogResult.Failure ->
                    if (previous is GenresState.Loaded) previous else GenresState.Failed(result.failure)
            }
            _uiState.update { it.copy(genres = next) }
        }
        genresJob = job
        return job
    }

    companion object {
        private const val FIRST_PAGE = 1

        fun factory(catalog: CatalogGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { DiscoverViewModel(catalog) }
        }
    }
}
