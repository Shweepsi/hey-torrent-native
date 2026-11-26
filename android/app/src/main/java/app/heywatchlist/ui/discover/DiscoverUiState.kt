package app.heywatchlist.ui.discover

import app.heywatchlist.core.CatalogFailure
import app.heywatchlist.core.Genre
import app.heywatchlist.core.Shelf
import app.heywatchlist.core.Title

/** Les rangées de l'écran Découvrir, dans l'ordre d'affichage. */
val DiscoverShelves: List<Shelf> = listOf(
    Shelf.TrendingWeek,
    Shelf.NowPlayingMovies,
    Shelf.UpcomingMovies,
    Shelf.PopularMovies,
    Shelf.PopularSeries,
    Shelf.OnTheAirSeries,
    Shelf.TopRatedMovies,
    Shelf.TopRatedSeries,
)

/** État d'une rangée : chacune charge, réussit ou échoue indépendamment des autres. */
sealed interface ShelfState {
    data object Loading : ShelfState

    /** [stale] : lue dans le cache de l'appareil faute de réseau. */
    data class Loaded(val titles: List<Title>, val stale: Boolean) : ShelfState

    data class Failed(val failure: CatalogFailure) : ShelfState
}

/** État de la rangée de genres de films. */
sealed interface GenresState {
    data object Loading : GenresState

    data class Loaded(val genres: List<Genre>) : GenresState

    data class Failed(val failure: CatalogFailure) : GenresState
}

data class DiscoverUiState(
    val shelves: Map<Shelf, ShelfState> = DiscoverShelves.associateWith { ShelfState.Loading },
    val genres: GenresState = GenresState.Loading,
    /** Un geste « tirer pour rafraîchir » est en cours. */
    val refreshing: Boolean = false,
) {
    fun shelf(shelf: Shelf): ShelfState = shelves[shelf] ?: ShelfState.Loading

    /** Une des rangées affichées vient du cache faute de réseau. */
    val isStale: Boolean
        get() = shelves.values.any { it is ShelfState.Loaded && it.stale }
}
