import Foundation
import Testing

@testable import HeyWatchlist

/// Traduction des types du cœur (UniFFI) vers ceux de l'app, et comportements
/// de `LiveCatalogClient` qui ne demandent aucun réseau : ni serveur, ni session.
struct LiveCatalogClientTests {
    private let summary = TitleSummary(
        id: 1399,
        mediaType: .tv,
        title: "Une série",
        year: 2011,
        releaseDate: "2011-04-17",
        overview: "Synopsis",
        posterUrl: "https://image.tmdb.org/t/p/w342/a.jpg",
        backdropUrl: nil,
        rating: 8.4,
        voteCount: 22_000,
        genreIds: [18, 10765]
    )

    private func makeDetail(videos: [Video]) -> TitleDetail {
        TitleDetail(
            summary: summary, tagline: nil, genres: [], runtimeMinutes: nil, status: nil,
            seasonsCount: nil, episodesCount: nil, seasons: [], cast: [], directors: [],
            videos: videos,
            providers: Providers(flatrate: [], rent: [], buy: []), similar: [], stale: false
        )
    }

    // MARK: Traduction

    @Test func aSummaryBecomesATitle() {
        let title = Title(core: summary)
        #expect(title.ref == TitleRef(kind: .series, id: 1399))
        #expect(title.name == "Une série")
        #expect(title.year == 2011)
        #expect(title.posterURL?.absoluteString == "https://image.tmdb.org/t/p/w342/a.jpg")
        #expect(title.backdropURL == nil)
        #expect(title.voteCount == 22_000)
    }

    @Test func aPageKeepsItsLengthAndItsStaleFlag() {
        let page = TitlePage(items: [summary], page: 2, totalPages: 7, stale: true)
        let list = TitleList(core: page)
        #expect(list.items.count == 1)
        #expect(list.totalPages == 7)
        #expect(list.stale)
        #expect(list.page == 2)
    }

    @Test func aDetailBecomesASheetWithItsTrailer() {
        let detail = TitleDetail(
            summary: summary,
            tagline: "",
            genres: [Genre(id: 18, name: "Drame")],
            runtimeMinutes: nil,
            status: "Ended",
            seasonsCount: 8,
            episodesCount: 73,
            seasons: [SeasonSummary(number: 1, name: "Saison 1", episodeCount: 10, airDate: "2011-04-17", posterUrl: nil)],
            cast: [CastMember(id: 7, name: "Camille Martin", character: "", profileUrl: nil)],
            directors: ["Sam Durand"],
            videos: [
                Video(youtubeKey: "teaser1", name: "Teaser", kind: .teaser),
                Video(youtubeKey: "abc_DEF-1", name: "Bande-annonce", kind: .trailer),
            ],
            providers: Providers(
                flatrate: [Provider(id: 8, name: "Plateforme", logoUrl: "https://image.tmdb.org/t/p/w92/l.jpg")],
                rent: [],
                buy: []
            ),
            similar: [summary],
            stale: false
        )
        let sheet = TitleSheet(core: detail)
        #expect(sheet.title.ref == TitleRef(kind: .series, id: 1399))
        #expect(sheet.tagline == nil)
        #expect(sheet.genres == [TitleGenre(id: 18, name: "Drame")])
        #expect(sheet.seasonsCount == 8)
        #expect(sheet.seasons.map(\.number) == [1])
        #expect(sheet.cast.first?.character == nil)
        #expect(sheet.directors == ["Sam Durand"])
        #expect(sheet.trailerURL?.absoluteString == "https://www.youtube.com/watch?v=abc_DEF-1")
        #expect(sheet.providers.flatrate.map(\.name) == ["Plateforme"])
        #expect(sheet.providers.isEmpty == false)
        #expect(sheet.similar.count == 1)
    }

    @Test func withoutATrailerATeaserIsUsed() {
        let sheet = TitleSheet(core: makeDetail(videos: [
            Video(youtubeKey: "clip1", name: "Extrait", kind: .clip),
            Video(youtubeKey: "teaser1", name: "Teaser", kind: .teaser),
        ]))
        #expect(sheet.trailerURL?.absoluteString == "https://www.youtube.com/watch?v=teaser1")
    }

    @Test func aClipOrAFeaturetteNeverPassesForATrailer() {
        let sheet = TitleSheet(core: makeDetail(videos: [
            Video(youtubeKey: "clip1", name: "Extrait", kind: .clip),
            Video(youtubeKey: "making1", name: "Making-of", kind: .featurette),
            Video(youtubeKey: "other1", name: "Autre", kind: .other),
        ]))
        #expect(sheet.trailerURL == nil)
        #expect(TitleSheet(core: makeDetail(videos: [])).trailerURL == nil)
    }

    @Test func aSeasonKeepsItsEpisodes() {
        let season = SeasonDetail(
            number: 1,
            name: "Saison 1",
            overview: "",
            episodes: [
                Episode(number: 1, name: "Pilote", overview: "Début", airDate: "2011-04-17", runtimeMinutes: 58, stillUrl: nil, rating: 8.1),
            ],
            stale: true
        )
        let episodes = SeasonEpisodes(core: season)
        #expect(episodes.episodes.map(\.name) == ["Pilote"])
        #expect(episodes.episodes.first?.runtimeMinutes == 58)
        #expect(episodes.stale)
    }

    @Test func everyShelfAndEveryKindHasItsOwnCoreCounterpart() {
        #expect(Set(DiscoverShelf.allCases.map(\.core)).count == DiscoverShelf.allCases.count)
        #expect(TitleKind.movie.core == .movie)
        #expect(TitleKind.series.core == .tv)
        #expect(TitleKind(core: .tv) == .series)
    }

    @Test func aVideoKeyIsEncodedInTheWatchAddress() {
        #expect(URL.youtubeVideo(key: "abc_DEF-1")?.absoluteString == "https://www.youtube.com/watch?v=abc_DEF-1")
        #expect(URL.youtubeVideo(key: "a b")?.absoluteString == "https://www.youtube.com/watch?v=a%20b")
    }

    // MARK: Sans réseau

    @Test func withoutAServerAddressTheCatalogSaysSo() async {
        let client = LiveCatalogClient(source: CoreAccountSource(baseUrl: "", store: MemoryStore()), cachePath: nil)
        await #expect(throws: CatalogFailure.notConfigured) { try await client.shelf(.trending, page: 1) }
    }

    @Test func withoutASessionTheCatalogIsSignedOutAndSaysSoOnce() async {
        let reports = Counter()
        let client = LiveCatalogClient(
            source: CoreAccountSource(baseUrl: "https://api.example.com", store: MemoryStore()),
            cachePath: nil,
            onSignedOut: { _ = await reports.next() }
        )
        await #expect(throws: CatalogFailure.signedOut) { try await client.sheet(of: TitleRef(kind: .movie, id: 603)) }
        #expect(await reports.next() == 2)
    }

    @Test func anEmptySearchNeedsNeitherSessionNorNetwork() async throws {
        let client = LiveCatalogClient(
            source: CoreAccountSource(baseUrl: "https://api.example.com", store: MemoryStore()),
            cachePath: nil
        )
        let list = try await client.search("   ", page: 1)
        #expect(list.items.isEmpty)
    }

    @Test func theCoreAccountSourceReturnsTheSameAccount() async throws {
        let source = CoreAccountSource(baseUrl: "https://api.example.com", store: MemoryStore())
        let first = try await source.account()
        let second = try await source.account()
        #expect(first === second)
    }
}
