package app.heywatchlist.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.CoreFailure
import app.heywatchlist.core.CoreFailureException
import app.heywatchlist.core.CoreGateway
import app.heywatchlist.core.UniffiCoreGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Interroge le cœur au démarrage et expose l'état de l'écran d'accueil. */
class HomeViewModel(private val gateway: CoreGateway) : ViewModel() {
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        _uiState.value = HomeUiState.Loading
        loadJob = viewModelScope.launch {
            _uiState.value = try {
                HomeUiState.Ready(gateway.load())
            } catch (error: CancellationException) {
                throw error
            } catch (error: CoreFailureException) {
                HomeUiState.Error(error.failure.messageRes)
            } catch (error: Exception) {
                HomeUiState.Error(CoreFailure.Unknown.messageRes)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(UniffiCoreGateway()) }
        }
    }
}
