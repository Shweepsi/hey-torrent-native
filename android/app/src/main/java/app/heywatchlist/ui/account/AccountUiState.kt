package app.heywatchlist.ui.account

import androidx.annotation.StringRes
import app.heywatchlist.core.Profile

/** État de l'app vis-à-vis du compte. */
sealed interface AccountUiState {
    /** Lecture de la session gardée sur l'appareil. */
    data object Starting : AccountUiState

    data class SignedOut(
        val busy: Boolean = false,
        @param:StringRes val errorRes: Int? = null,
    ) : AccountUiState

    /** Connecté ; tant que `profile.access` n'est pas actif, l'app montre l'écran d'attente. */
    data class SignedIn(
        val profile: Profile,
        val coreVersion: String,
        val busy: Boolean = false,
        val confirmingDeletion: Boolean = false,
        /** Écran d'attente : relecture de l'accès demandée par la personne. */
        val checking: Boolean = false,
        @param:StringRes val errorRes: Int? = null,
    ) : AccountUiState
}
