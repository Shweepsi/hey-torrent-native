package app.heywatchlist.core

import androidx.annotation.StringRes
import app.heywatchlist.R

/**
 * Cause d'un échec du cœur, traduite en texte français par ressource.
 * Indépendante des types générés par UniFFI : l'écran et ses tests n'en ont
 * pas besoin.
 */
enum class CoreFailure(@param:StringRes val messageRes: Int) {
    /** Le cœur a signalé `CoreError::Internal`. */
    Internal(R.string.error_core_internal),

    /** La bibliothèque native n'a pas pu être chargée. */
    Unavailable(R.string.error_core_unavailable),

    /** Toute autre erreur, y compris un type d'erreur du cœur pas encore traduit. */
    Unknown(R.string.error_unknown),
}

/** Échec d'un appel au cœur, porteur de sa [CoreFailure]. */
class CoreFailureException(val failure: CoreFailure, cause: Throwable? = null) : Exception(failure.name, cause)
