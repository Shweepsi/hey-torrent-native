package app.heywatchlist.core

import uniffi.heywatchlist_core.CastMember as CoreCastMember
import uniffi.heywatchlist_core.CatalogException
import uniffi.heywatchlist_core.Episode as CoreEpisode
import uniffi.heywatchlist_core.Genre as CoreGenre
import uniffi.heywatchlist_core.MediaType as CoreMediaType
import uniffi.heywatchlist_core.Provider as CoreProvider
import uniffi.heywatchlist_core.Providers as CoreProviders
import uniffi.heywatchlist_core.SeasonDetail as CoreSeasonDetail
import uniffi.heywatchlist_core.SeasonSummary as CoreSeasonSummary
import uniffi.heywatchlist_core.Shelf as CoreShelf
import uniffi.heywatchlist_core.TitleDetail as CoreTitleDetail
import uniffi.heywatchlist_core.TitlePage as CoreTitlePage
import uniffi.heywatchlist_core.TitleSummary as CoreTitleSummary
import uniffi.heywatchlist_core.Video as CoreVideo
import uniffi.heywatchlist_core.VideoKind as CoreVideoKind

// Traduction des types générés par UniFFI vers ceux de l'app. Les nombres non
// signés du cœur (u32 → UInt, u64 → ULong) deviennent des Int et des Long : les
// valeurs viennent de TMDB (identifiants de dix chiffres au plus, comptes
// bornés) et ne débordent jamais.

internal fun MediaKind.toCore(): CoreMediaType = when (this) {
    MediaKind.Movie -> CoreMediaType.MOVIE
    MediaKind.Tv -> CoreMediaType.TV
}

internal fun Shelf.toCore(): CoreShelf = when (this) {
    Shelf.TrendingWeek -> CoreShelf.TRENDING_WEEK
    Shelf.PopularMovies -> CoreShelf.POPULAR_MOVIES
    Shelf.NowPlayingMovies -> CoreShelf.NOW_PLAYING_MOVIES
    Shelf.UpcomingMovies -> CoreShelf.UPCOMING_MOVIES
    Shelf.TopRatedMovies -> CoreShelf.TOP_RATED_MOVIES
    Shelf.PopularSeries -> CoreShelf.POPULAR_SERIES
    Shelf.OnTheAirSeries -> CoreShelf.ON_THE_AIR_SERIES
    Shelf.TopRatedSeries -> CoreShelf.TOP_RATED_SERIES
}

private fun CoreMediaType.toKind(): MediaKind = when (this) {
    CoreMediaType.MOVIE -> MediaKind.Movie
    CoreMediaType.TV -> MediaKind.Tv
}

private fun CoreVideoKind.toKind(): VideoKind = when (this) {
    CoreVideoKind.TRAILER -> VideoKind.Trailer
    CoreVideoKind.TEASER -> VideoKind.Teaser
    CoreVideoKind.CLIP -> VideoKind.Clip
    CoreVideoKind.FEATURETTE -> VideoKind.Featurette
    CoreVideoKind.OTHER -> VideoKind.Other
}

internal fun CoreTitleSummary.toTitle() = Title(
    id = id.toLong(),
    kind = mediaType.toKind(),
    title = title,
    year = year?.toInt(),
    releaseDate = releaseDate,
    overview = overview,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    rating = rating,
    voteCount = voteCount.toInt(),
    genreIds = genreIds.map { it.toInt() },
)

internal fun CoreTitlePage.toPage() = TitlePage(
    items = items.map { it.toTitle() },
    page = page.toInt(),
    totalPages = totalPages.toInt(),
    stale = stale,
)

internal fun CoreGenre.toGenre() = Genre(id = id.toInt(), name = name)

private fun CoreCastMember.toCast() = CastMember(name = name, character = character, profileUrl = profileUrl)

private fun CoreVideo.toVideo() = Video(youtubeKey = youtubeKey, kind = kind.toKind())

private fun CoreProvider.toProvider() = Provider(name = name, logoUrl = logoUrl)

private fun CoreProviders.toProviders() = Providers(
    flatrate = flatrate.map { it.toProvider() },
    rent = rent.map { it.toProvider() },
    buy = buy.map { it.toProvider() },
)

private fun CoreSeasonSummary.toSeason() = SeasonSummary(
    number = number.toInt(),
    name = name,
    episodeCount = episodeCount.toInt(),
    airDate = airDate,
    posterUrl = posterUrl,
)

internal fun CoreTitleDetail.toDetail() = TitleDetail(
    summary = summary.toTitle(),
    tagline = tagline,
    genres = genres.map { it.toGenre() },
    runtimeMinutes = runtimeMinutes?.toInt(),
    seasonsCount = seasonsCount?.toInt(),
    seasons = seasons.map { it.toSeason() },
    cast = cast.map { it.toCast() },
    directors = directors,
    videos = videos.map { it.toVideo() },
    providers = providers.toProviders(),
    similar = similar.map { it.toTitle() },
    stale = stale,
)

private fun CoreEpisode.toEpisode() = Episode(
    number = number.toInt(),
    name = name,
    overview = overview,
    airDate = airDate,
    runtimeMinutes = runtimeMinutes?.toInt(),
    stillUrl = stillUrl,
)

internal fun CoreSeasonDetail.toSeasonDetail() = SeasonDetail(
    number = number.toInt(),
    name = name,
    overview = overview,
    episodes = episodes.map { it.toEpisode() },
    stale = stale,
)

internal fun CatalogException.toFailure(): CatalogFailure = when (this) {
    is CatalogException.Network -> CatalogFailure.Network
    is CatalogException.SignedOut -> CatalogFailure.SignedOut
    is CatalogException.NotFound -> CatalogFailure.NotFound
    is CatalogException.Unavailable -> CatalogFailure.Unavailable
    is CatalogException.Server -> CatalogFailure.Server
    is CatalogException.Unexpected,
    is CatalogException.Internal,
    -> CatalogFailure.Unknown
}

/** Échec levé en créant le compte du cœur (adresse absente, bibliothèque native illisible…). */
internal fun AccountFailure.toCatalogFailure(): CatalogFailure = when (this) {
    AccountFailure.NotConfigured -> CatalogFailure.NotConfigured
    AccountFailure.Unavailable -> CatalogFailure.CoreUnavailable
    AccountFailure.Network -> CatalogFailure.Network
    AccountFailure.Server -> CatalogFailure.Server
    AccountFailure.SignedOut -> CatalogFailure.SignedOut
    AccountFailure.ProviderUnavailable -> CatalogFailure.Unavailable
    AccountFailure.InvalidIdentity,
    AccountFailure.InvitationInvalid,
    AccountFailure.Cancelled,
    AccountFailure.Unknown,
    -> CatalogFailure.Unknown
}
