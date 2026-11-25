package app.heywatchlist.ui.account

import androidx.annotation.StringRes
import app.heywatchlist.core.Profile

/** État de l'app vis-à-vis du compte. */
sealed interface AccountUiState {
    /** Lecture de la session gardée sur l'appareil. */
    data object Starting : AccountUiState

    data class SignedOut(
        val busy: Boolean = false,
        /** Le serveur ne connaît pas cette personne : on demande son code. */
        val askInvitation: Boolean = false,
        @param:StringRes val errorRes: Int? = null,
    ) : AccountUiState

    data class SignedIn(
        val profile: Profile,
        val coreVersion: String,
        val busy: Boolean = false,
        val confirmingDeletion: Boolean = false,
        @param:StringRes val errorRes: Int? = null,
    ) : AccountUiState
}
