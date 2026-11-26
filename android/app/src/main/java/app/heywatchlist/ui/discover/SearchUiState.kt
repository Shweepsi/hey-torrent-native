package app.heywatchlist.ui.discover

import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.Title

/** Ce que montre la liste de résultats. */
sealed interface SearchState {
    /** Rien de saisi. */
    data object Idle : SearchState

    /** Première page en cours de lecture (squelettes). */
    data object Loading : SearchState

    /**
     * [nextPage] : page à lire au défilement, `null` à la dernière. [moreFailure] :
     * la lecture de la page suivante a échoué (on propose de réessayer).
     */
    data class Results(
        val titles: List<Title>,
        val nextPage: Int?,
        val loadingMore: Boolean = false,
        val moreFailure: CatalogFailure? = null,
    ) : SearchState

    data class Failed(val failure: CatalogFailure) : SearchState
}

/** [query] : la requête en cours, telle que saisie. */
data class SearchUiState(val query: String = "", val state: SearchState = SearchState.Idle)
