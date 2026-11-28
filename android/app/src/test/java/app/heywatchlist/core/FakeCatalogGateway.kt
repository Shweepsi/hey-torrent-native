package app.heywatchlist.core

/**
 * Catalogue simulé pour les tests : chaque lecture appelle la fonction `on…`
 * correspondante (qui peut suspendre ou lever [CatalogFailureException]) et
 * garde la trace de ses appels.
 */
class FakeCatalogGateway : CatalogGateway {
    var onShelf: suspend (Shelf, Int) -> TitlePage = { _, _ -> pageOf() }
    var onGenres: suspend (MediaKind) -> List<Genre> = { emptyList() }
    var onByGenre: suspend (MediaKind, Int, Int) -> TitlePage = { _, _, _ -> pageOf() }
    var onSearch: suspend (String, Int) -> TitlePage = { _, _ -> pageOf() }
    var onDetail: suspend (MediaKind, Long) -> TitleDetail = { kind, id -> detailOf(titleOf(id, kind)) }
    var onSeason: suspend (Long, Int) -> SeasonDetail = { _, number -> seasonOf(number) }

    val shelfCalls = mutableListOf<Pair<Shelf, Int>>()
    val genreCalls = mutableListOf<MediaKind>()
    val byGenreCalls = mutableListOf<Triple<MediaKind, Int, Int>>()
    val searchCalls = mutableListOf<Pair<String, Int>>()
    val detailCalls = mutableListOf<Pair<MediaKind, Long>>()
    val seasonCalls = mutableListOf<Pair<Long, Int>>()

    override suspend fun shelf(shelf: Shelf, page: Int): TitlePage {
        shelfCalls += shelf to page
        return onShelf(shelf, page)
    }

    override suspend fun genres(kind: MediaKind): List<Genre> {
        genreCalls += kind
        return onGenres(kind)
    }

    override suspend fun byGenre(kind: MediaKind, genreId: Int, page: Int): TitlePage {
        byGenreCalls += Triple(kind, genreId, page)
        return onByGenre(kind, genreId, page)
    }

    override suspend fun search(query: String, page: Int): TitlePage {
        searchCalls += query to page
        return onSearch(query, page)
    }

    override suspend fun detail(kind: MediaKind, id: Long): TitleDetail {
        detailCalls += kind to id
        return onDetail(kind, id)
    }

    override suspend fun season(tvId: Long, number: Int): SeasonDetail {
        seasonCalls += tvId to number
        return onSeason(tvId, number)
    }
}

fun titleOf(id: Long, kind: MediaKind = MediaKind.Movie, title: String = "Titre $id") = Title(
    id = id,
    kind = kind,
    title = title,
    year = 2024,
    releaseDate = "2024-03-12",
    overview = "Résumé",
    posterUrl = null,
    backdropUrl = null,
    rating = 7.5,
    voteCount = 100,
    genreIds = emptyList(),
)

fun pageOf(
    vararg items: Title,
    page: Int = 1,
    totalPages: Int = 1,
    stale: Boolean = false,
) = TitlePage(items.toList(), page, totalPages, stale)

fun detailOf(summary: Title, seasons: List<SeasonSummary> = emptyList()) = TitleDetail(
    summary = summary,
    tagline = null,
    genres = emptyList(),
    runtimeMinutes = 120,
    seasonsCount = seasons.size.takeIf { it > 0 },
    seasons = seasons,
    cast = emptyList(),
    directors = emptyList(),
    videos = emptyList(),
    similar = emptyList(),
    stale = false,
)

fun seasonOf(number: Int) = SeasonDetail(
    number = number,
    name = "Saison $number",
    overview = "",
    episodes = listOf(Episode(1, "Épisode 1", "", null, 42, null)),
    stale = false,
)

fun failing(failure: CatalogFailure): Nothing = throw CatalogFailureException(failure)
