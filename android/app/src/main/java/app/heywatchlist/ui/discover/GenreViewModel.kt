package app.heywatchlist.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.CatalogResult
import app.heywatchlist.core.Genre
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.TitlePage
import app.heywatchlist.core.attempt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Titres d'un genre, en films ou en séries, page après page. [genre] est un
 * genre de film (les pastilles de l'écran Découvrir) : il part tel quel au
 * cœur, qui le traduit pour les séries. Un genre sans équivalent en série
 * revient comme une page vide.
 */
class GenreViewModel(private val catalog: CatalogGateway, private val genre: Genre) : ViewModel() {
    private val _uiState = MutableStateFlow(GenreUiState())
    val uiState: StateFlow<GenreUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var moreJob: Job? = null

    /** Numéro de la lecture en cours : une réponse d'un autre numéro est obsolète. */
    private var generation = 0

    init {
        load()
    }

    fun select(kind: MediaKind) {
        if (kind == _uiState.value.kind) return
        _uiState.value = GenreUiState(kind = kind)
        load()
    }

    fun retry() {
        if (_uiState.value.list is GenreListState.Failed) {
            _uiState.update { it.copy(list = GenreListState.Loading) }
            load()
        }
    }

    /** Lit la page suivante, si elle existe et si elle n'est pas déjà en cours. */
    fun loadMore() {
        val loaded = _uiState.value.list as? GenreListState.Loaded ?: return
        val page = loaded.nextPage ?: return
        if (loaded.loadingMore) return
        val mine = generation
        val kind = _uiState.value.kind
        updateLoaded { it.copy(loadingMore = true, moreFailure = null) }
        moreJob = viewModelScope.launch {
            val result = attempt { catalog.byGenre(kind, genre.id, page) }
            if (mine != generation) return@launch
            when (result) {
                is CatalogResult.Success -> updateLoaded { current ->
                    val titles = (current.titles + result.value.items).distinctBy { it.key }
                    current.copy(
                        titles = titles,
                        nextPage = nextPageOf(result.value),
                        stale = current.stale || result.value.stale,
                        loadingMore = false,
                    )
                }

                is CatalogResult.Failure -> updateLoaded { it.copy(loadingMore = false, moreFailure = result.failure) }
            }
        }
    }

    private fun load() {
        val mine = ++generation
        job?.cancel()
        moreJob?.cancel()
        val kind = _uiState.value.kind
        job = viewModelScope.launch {
            val next = when (val result = attempt { catalog.byGenre(kind, genre.id, FIRST_PAGE) }) {
                is CatalogResult.Success -> GenreListState.Loaded(
                    titles = result.value.items.distinctBy { it.key },
                    nextPage = nextPageOf(result.value),
                    stale = result.value.stale,
                )

                is CatalogResult.Failure -> GenreListState.Failed(result.failure)
            }
            if (mine != generation) return@launch
            _uiState.update { it.copy(list = next) }
        }
    }

    private fun updateLoaded(transform: (GenreListState.Loaded) -> GenreListState.Loaded) {
        _uiState.update { current ->
            val list = current.list
            if (list is GenreListState.Loaded) current.copy(list = transform(list)) else current
        }
    }

    private fun nextPageOf(page: TitlePage): Int? = if (page.page < page.totalPages) page.page + 1 else null

    companion object {
        private const val FIRST_PAGE = 1

        fun factory(catalog: CatalogGateway, genre: Genre): ViewModelProvider.Factory = viewModelFactory {
            initializer { GenreViewModel(catalog, genre) }
        }
    }
}
