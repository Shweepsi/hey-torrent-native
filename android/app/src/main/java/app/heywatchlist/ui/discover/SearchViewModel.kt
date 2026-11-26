package app.heywatchlist.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.CatalogResult
import app.heywatchlist.core.TitlePage
import app.heywatchlist.core.attempt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Recherche instantanée : la lecture part [debounceMillis] après la dernière
 * frappe, annule la précédente, et une réponse devenue obsolète est ignorée.
 * Les pages suivantes se lisent au défilement.
 */
class SearchViewModel(
    private val catalog: CatalogGateway,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var moreJob: Job? = null

    /** Numéro de la requête en cours : une réponse d'un autre numéro est obsolète. */
    private var generation = 0

    fun onQueryChange(text: String) {
        val previous = _uiState.value
        _uiState.update { it.copy(query = text) }
        val query = text.trim()
        // Une espace de plus ou de moins ne change pas la recherche : on garde la lecture en cours
        // ou ses résultats, sans les remplacer par des squelettes.
        if (query.isNotEmpty() && query == previous.query.trim() && previous.state !is SearchState.Failed) return
        cancelReads()
        if (query.isEmpty()) {
            _uiState.update { it.copy(state = SearchState.Idle) }
            return
        }
        start(query, waitForTyping = true)
    }

    /** Relit la requête en cours après un échec, sans attendre. */
    fun retry() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.state !is SearchState.Failed) return
        cancelReads()
        start(query, waitForTyping = false)
    }

    /** Lit la page suivante des résultats, si elle existe et si elle n'est pas déjà en cours. */
    fun loadMore() {
        // Tant qu'une nouvelle requête attend la fin de la frappe, les résultats affichés sont encore
        // ceux de la précédente : lire leur page suivante avec la nouvelle requête mêlerait les deux.
        if (searchJob?.isActive == true) return
        val results = _uiState.value.state as? SearchState.Results ?: return
        val page = results.nextPage ?: return
        if (results.loadingMore) return
        val mine = generation
        val query = _uiState.value.query.trim()
        updateResults { it.copy(loadingMore = true, moreFailure = null) }
        moreJob = viewModelScope.launch {
            val result = attempt { catalog.search(query, page) }
            if (mine != generation) return@launch
            when (result) {
                is CatalogResult.Success -> updateResults { current ->
                    val titles = (current.titles + result.value.items).distinctBy { it.key }
                    current.copy(titles = titles, nextPage = nextPageOf(result.value), loadingMore = false)
                }

                is CatalogResult.Failure ->
                    updateResults { it.copy(loadingMore = false, moreFailure = result.failure) }
            }
        }
    }

    private fun start(query: String, waitForTyping: Boolean) {
        val mine = ++generation
        searchJob = viewModelScope.launch {
            if (waitForTyping) delay(debounceMillis)
            _uiState.update { it.copy(state = SearchState.Loading) }
            val result = attempt { catalog.search(query, FIRST_PAGE) }
            if (mine != generation) return@launch
            val next = when (result) {
                is CatalogResult.Success -> SearchState.Results(
                    titles = result.value.items.distinctBy { it.key },
                    nextPage = nextPageOf(result.value),
                )

                is CatalogResult.Failure -> SearchState.Failed(result.failure)
            }
            _uiState.update { it.copy(state = next) }
        }
    }

    /** Arrête toute lecture en cours et rend obsolète sa réponse. */
    private fun cancelReads() {
        generation++
        searchJob?.cancel()
        moreJob?.cancel()
    }

    private fun updateResults(transform: (SearchState.Results) -> SearchState.Results) {
        _uiState.update { current ->
            val state = current.state
            if (state is SearchState.Results) current.copy(state = transform(state)) else current
        }
    }

    private fun nextPageOf(page: TitlePage): Int? = if (page.page < page.totalPages) page.page + 1 else null

    companion object {
        /** Attente après la dernière frappe avant de lire. */
        const val DEBOUNCE_MILLIS = 350L
        private const val FIRST_PAGE = 1

        fun factory(catalog: CatalogGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(catalog) }
        }
    }
}
