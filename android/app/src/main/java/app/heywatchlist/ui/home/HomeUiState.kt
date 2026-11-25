package app.heywatchlist.ui.home

import androidx.annotation.StringRes
import app.heywatchlist.core.CoreInfo

/** État de l'écran d'accueil. */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Ready(val info: CoreInfo) : HomeUiState

    /** Échec, avec la ressource française du message à afficher. */
    data class Error(@param:StringRes val messageRes: Int) : HomeUiState
}
