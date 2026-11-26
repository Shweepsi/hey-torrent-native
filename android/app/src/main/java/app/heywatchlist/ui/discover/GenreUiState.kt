package app.heywatchlist.ui.discover

import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.Title

/** Ce que montre la grille d'un genre. */
sealed interface GenreListState {
    data object Loading : GenreListState

    /**
     * [nextPage] : page à lire au défilement, `null` à la dernière. [stale] : une
     * des pages lues vient du cache de l'appareil faute de réseau.
     */
    data class Loaded(
        val titles: List<Title>,
        val nextPage: Int?,
        val stale: Boolean = false,
        val loadingMore: Boolean = false,
        val moreFailure: CatalogFailure? = null,
    ) : GenreListState

    data class Failed(val failure: CatalogFailure) : GenreListState
}

data class GenreUiState(
    val kind: MediaKind = MediaKind.Movie,
    val list: GenreListState = GenreListState.Loading,
)
