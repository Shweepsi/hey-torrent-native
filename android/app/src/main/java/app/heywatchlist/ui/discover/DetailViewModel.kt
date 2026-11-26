package app.heywatchlist.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.CatalogGateway
import app.heywatchlist.core.CatalogResult
import app.heywatchlist.core.Title
import app.heywatchlist.core.attempt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Fiche d'un film ou d'une série, et épisodes des saisons que l'on ouvre. */
class DetailViewModel(private val catalog: CatalogGateway, private val title: Title) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading(title))
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val seasonJobs = mutableMapOf<Int, Job>()

    init {
        load()
    }

    fun retry() {
        if (_uiState.value is DetailUiState.Failed) {
            _uiState.value = DetailUiState.Loading(title)
            load()
        }
    }

    /** Ouvre les épisodes d'une saison (en les lisant si besoin), ou les referme si elle est déjà ouverte. */
    fun toggleSeason(number: Int) {
        val current = _uiState.value as? DetailUiState.Loaded ?: return
        if (current.expandedSeason == number) {
            _uiState.value = current.copy(expandedSeason = null)
            return
        }
        _uiState.value = current.copy(expandedSeason = number)
        if (current.seasons[number] !is SeasonState.Loaded) loadSeason(number)
    }

    fun retrySeason(number: Int) {
        val current = _uiState.value as? DetailUiState.Loaded ?: return
        if (current.seasons[number] is SeasonState.Failed) loadSeason(number)
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = when (val result = attempt { catalog.detail(title.kind, title.id) }) {
                is CatalogResult.Success -> DetailUiState.Loaded(result.value)
                is CatalogResult.Failure -> DetailUiState.Failed(title, result.failure)
            }
        }
    }

    private fun loadSeason(number: Int) {
        seasonJobs[number]?.cancel()
        setSeason(number, SeasonState.Loading)
        seasonJobs[number] = viewModelScope.launch {
            val next = when (val result = attempt { catalog.season(title.id, number) }) {
                is CatalogResult.Success -> SeasonState.Loaded(result.value)
                is CatalogResult.Failure -> SeasonState.Failed(result.failure)
            }
            setSeason(number, next)
        }
    }

    private fun setSeason(number: Int, state: SeasonState) {
        _uiState.update { current ->
            if (current is DetailUiState.Loaded) current.copy(seasons = current.seasons + (number to state)) else current
        }
    }

    companion object {
        fun factory(catalog: CatalogGateway, title: Title): ViewModelProvider.Factory = viewModelFactory {
            initializer { DetailViewModel(catalog, title) }
        }
    }
}
