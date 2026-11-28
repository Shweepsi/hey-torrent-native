package app.heywatchlist.screenshots

import app.heywatchlist.core.CastMember
import app.heywatchlist.core.Genre
import app.heywatchlist.core.MediaKind
import app.heywatchlist.core.RequestState
import app.heywatchlist.core.SeasonSummary
import app.heywatchlist.core.Title
import app.heywatchlist.core.TitleDetail
import app.heywatchlist.core.Video
import app.heywatchlist.core.VideoKind
import app.heywatchlist.core.WatchlistEntry

/** Catalogue inventé pour les captures : titres fictifs, images factices (`fake://`). */
object SampleData {
    private val names = listOf(
        "Les Veilleurs du phare" to MediaKind.Movie,
        "Neige sur Valparaíso" to MediaKind.Movie,
        "La Ligne bleue" to MediaKind.Tv,
        "Orages d'été" to MediaKind.Movie,
        "Le Dernier Quai" to MediaKind.Movie,
        "Station Méridienne" to MediaKind.Tv,
        "Comme un battement d'ailes" to MediaKind.Movie,
        "Les Cartographes" to MediaKind.Tv,
        "Minuit à Tanger" to MediaKind.Movie,
        "Le Jardin des machines" to MediaKind.Movie,
    )

    fun title(index: Int, offset: Int = 0): Title {
        val (name, kind) = names[(index + offset) % names.size]
        val id = (index + offset + 1).toLong()
        return Title(
            id = id,
            kind = kind,
            title = name,
            year = 2018 + (id % 8).toInt(),
            releaseDate = "2026-0${1 + id % 9}-1${id % 9}",
            overview = "Une histoire inventée pour les captures d'écran : une famille, un secret, et un été qui change tout.",
            posterUrl = "fake://poster/$id",
            backdropUrl = "fake://backdrop/$id",
            rating = 6.2 + (id % 7) * 0.4,
            voteCount = 1200 + id.toInt() * 37,
            genreIds = listOf(18),
        )
    }

    fun titles(count: Int, offset: Int = 0): List<Title> = List(count) { title(it, offset) }

    val genres = listOf("Action", "Animation", "Comédie", "Drame", "Science-fiction", "Thriller")
        .mapIndexed { i, name -> Genre(id = i + 1, name = name) }

    /** Fiche d'une série, avec saisons, distribution et titres similaires. */
    val seriesDetail: TitleDetail
        get() {
            val summary = title(2)
            return TitleDetail(
                summary = summary,
                tagline = "Chaque frontière a son prix.",
                genres = listOf(Genre(18, "Drame"), Genre(80, "Crime"), Genre(9648, "Mystère")),
                runtimeMinutes = 52,
                seasonsCount = 3,
                seasons = (1..3).map { n ->
                    SeasonSummary(number = n, name = "Saison $n", episodeCount = 8, airDate = "202${n + 1}-03-01", posterUrl = "fake://poster/s$n")
                },
                cast = listOf("Camille Roux" to "Inès", "Malik Benali" to "Yanis", "Jeanne Morel" to "Lucie", "Hugo Lambert" to "Paul")
                    .mapIndexed { i, (name, role) -> CastMember(name = name, character = role, profileUrl = "fake://profile/$i") },
                directors = listOf("Sarah Lenoir"),
                videos = listOf(Video(youtubeKey = "exemple", kind = VideoKind.Trailer)),
                similar = titles(6, offset = 4),
                stale = false,
            )
        }

    /** Watchlist : un titre dans chaque état. */
    val watchlist: List<WatchlistEntry> = listOf(
        RequestState.Available to null,
        RequestState.InProgress to null,
        RequestState.Requested to null,
        RequestState.Refused to "Pas encore sorti en France",
        RequestState.Requested to null,
    ).mapIndexed { i, (state, reason) ->
        val title = title(i, offset = 1)
        WatchlistEntry(
            kind = title.kind,
            id = title.id,
            title = title.title,
            year = title.year,
            posterUrl = title.posterUrl,
            seasons = if (title.kind == MediaKind.Tv) listOf(1, 2) else null,
            addedAt = 1_790_000_000L - i * 86_400L,
            state = state,
            refusalReason = reason,
            pending = i == 4,
        )
    }
}
