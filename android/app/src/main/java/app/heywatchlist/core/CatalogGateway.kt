package app.heywatchlist.core

import androidx.annotation.StringRes
import app.heywatchlist.R
import kotlinx.coroutines.CancellationException

/** Film ou série. */
enum class MediaKind { Movie, Tv }

/** Une liste du catalogue, une rangée de l'écran Découvrir. */
enum class Shelf {
    /** Films et séries mêlés. */
    TrendingWeek,
    PopularMovies,
    NowPlayingMovies,
    UpcomingMovies,
    TopRatedMovies,
    PopularSeries,
    OnTheAirSeries,
    TopRatedSeries,
}

/** Un titre dans une liste : affiche, titre, année, note. */
data class Title(
    val id: Long,
    val kind: MediaKind,
    val title: String,
    val year: Int?,
    /** `AAAA-MM-JJ`. */
    val releaseDate: String?,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    /** Note moyenne sur 10 ; 0 sans vote. */
    val rating: Double,
    val voteCount: Int,
    val genreIds: List<Int>,
) {
    /** Clé stable et unique d'un titre dans une liste (un film et une série peuvent partager un numéro). */
    val key: String get() = "${kind.name}-$id"
}

/** Une page de titres. [stale] : lue dans le cache de l'appareil faute de réseau. */
data class TitlePage(val items: List<Title>, val page: Int, val totalPages: Int, val stale: Boolean)

data class Genre(val id: Int, val name: String)

data class CastMember(val name: String, val character: String?, val profileUrl: String?)

enum class VideoKind { Trailer, Teaser, Clip, Featurette, Other }

/** Vidéo hébergée par YouTube. */
data class Video(val youtubeKey: String, val kind: VideoKind)

data class Provider(val name: String, val logoUrl: String?)

/** Où regarder le titre en France. */
data class Providers(val flatrate: List<Provider>, val rent: List<Provider>, val buy: List<Provider>) {
    val isEmpty: Boolean get() = flatrate.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

data class SeasonSummary(
    val number: Int,
    val name: String,
    val episodeCount: Int,
    val airDate: String?,
    val posterUrl: String?,
)

/** Fiche complète d'un film ou d'une série. */
data class TitleDetail(
    val summary: Title,
    val tagline: String?,
    val genres: List<Genre>,
    /** Film : durée ; série : durée d'un épisode. */
    val runtimeMinutes: Int?,
    val seasonsCount: Int?,
    val seasons: List<SeasonSummary>,
    val cast: List<CastMember>,
    /** Réalisateurs (film) ou créateurs (série). */
    val directors: List<String>,
    /** Bandes-annonces d'abord. */
    val videos: List<Video>,
    val providers: Providers,
    val similar: List<Title>,
    val stale: Boolean,
) {
    /** La vidéo à proposer : une bande-annonce, à défaut un teaser. */
    val trailer: Video?
        get() = videos.firstOrNull { it.kind == VideoKind.Trailer } ?: videos.firstOrNull { it.kind == VideoKind.Teaser }
}

data class Episode(
    val number: Int,
    val name: String,
    val overview: String,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val stillUrl: String?,
)

data class SeasonDetail(
    val number: Int,
    val name: String,
    val overview: String,
    val episodes: List<Episode>,
    val stale: Boolean,
)

/**
 * Cause d'un échec de lecture du catalogue, traduite en français par ressource.
 * Indépendante des types générés par UniFFI, comme [AccountFailure].
 */
enum class CatalogFailure(@param:StringRes val messageRes: Int) {
    Network(R.string.catalog_error_network),
    SignedOut(R.string.catalog_error_signed_out),
    NotFound(R.string.catalog_error_not_found),
    Unavailable(R.string.catalog_error_unavailable),
    Server(R.string.catalog_error_server),

    /** Adresse du serveur absente de cette compilation de l'app. */
    NotConfigured(R.string.catalog_error_not_configured),

    /** La bibliothèque native n'a pas pu être chargée. */
    CoreUnavailable(R.string.catalog_error_core_unavailable),
    Unknown(R.string.catalog_error_unknown),
}

class CatalogFailureException(val failure: CatalogFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

/**
 * Frontière entre l'interface et le catalogue du cœur Rust. Chaque méthode
 * s'exécute hors du fil principal et lève [CatalogFailureException]. Les pages
 * se comptent à partir de 1.
 */
interface CatalogGateway {
    suspend fun shelf(shelf: Shelf, page: Int): TitlePage

    /** Genres de films ou de séries, en français. */
    suspend fun genres(kind: MediaKind): List<Genre>

    /** Titres d'un genre, du plus populaire au moins populaire. */
    suspend fun byGenre(kind: MediaKind, genreId: Int, page: Int): TitlePage

    /** Une requête vide rend une page vide sans appel réseau. */
    suspend fun search(query: String, page: Int): TitlePage

    suspend fun detail(kind: MediaKind, id: Long): TitleDetail

    suspend fun season(tvId: Long, number: Int): SeasonDetail
}

/** Résultat d'une lecture : la valeur, ou la cause de l'échec. */
sealed interface CatalogResult<out T> {
    data class Success<out T>(val value: T) : CatalogResult<T>

    data class Failure(val failure: CatalogFailure) : CatalogResult<Nothing>
}

/**
 * Exécute [block] et range son échec dans un [CatalogResult]. Une erreur
 * imprévue devient [CatalogFailure.Unknown] : un défaut du cœur ne doit pas
 * faire tomber l'écran. L'annulation, elle, passe toujours.
 */
suspend fun <T> attempt(block: suspend () -> T): CatalogResult<T> = try {
    CatalogResult.Success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: CatalogFailureException) {
    CatalogResult.Failure(error.failure)
} catch (error: Exception) {
    CatalogResult.Failure(CatalogFailure.Unknown)
}
