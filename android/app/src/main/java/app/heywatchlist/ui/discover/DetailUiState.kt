package app.heywatchlist.ui.discover

import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.SeasonDetail
import app.heywatchlist.core.Title
import app.heywatchlist.core.TitleDetail

/** État de la fiche d'un titre. [preview] vient de la liste d'où on arrive : elle s'affiche tout de suite. */
sealed interface DetailUiState {
    val preview: Title

    data class Loading(override val preview: Title) : DetailUiState

    data class Failed(override val preview: Title, val failure: CatalogFailure) : DetailUiState

    /**
     * [expandedSeason] : saison dont on voit les épisodes. [seasons] garde les
     * épisodes déjà lus, par numéro de saison.
     */
    data class Loaded(
        val detail: TitleDetail,
        val expandedSeason: Int? = null,
        val seasons: Map<Int, SeasonState> = emptyMap(),
    ) : DetailUiState {
        override val preview: Title get() = detail.summary
    }
}

/** État des épisodes d'une saison. */
sealed interface SeasonState {
    data object Loading : SeasonState

    data class Loaded(val season: SeasonDetail) : SeasonState

    data class Failed(val failure: CatalogFailure) : SeasonState
}
