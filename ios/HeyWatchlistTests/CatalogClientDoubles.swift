import Foundation

@testable import HeyWatchlist

// MARK: Données d'exemple

extension Title {
    static func sample(_ id: Int, kind: TitleKind = .movie, backdrop: Bool = true) -> Title {
        Title(
            ref: TitleRef(kind: kind, id: id),
            name: "Titre \(id)",
            year: 2020,
            overview: "Synopsis \(id)",
            posterURL: URL(string: "https://images.example/p/\(id).jpg"),
            backdropURL: backdrop ? URL(string: "https://images.example/b/\(id).jpg") : nil,
            rating: 7.5,
            voteCount: 100
        )
    }
}

extension TitleList {
    static func sample(
        _ ids: [Int],
        kind: TitleKind = .movie,
        totalPages: Int = 1,
        stale: Bool = false
    ) -> TitleList {
        TitleList(items: ids.map { Title.sample($0, kind: kind) }, totalPages: totalPages, stale: stale)
    }

    var ids: [Int] { items.map(\.ref.id) }
}

extension SeasonEntry {
    static func sample(_ number: Int) -> SeasonEntry {
        SeasonEntry(number: number, name: "Saison \(number)", episodeCount: 2, airDate: "2019-04-12", posterURL: nil)
    }
}

extension TitleSheet {
    static func sample(_ ref: TitleRef, stale: Bool = false, seasons: [SeasonEntry] = []) -> TitleSheet {
        TitleSheet(
            title: .sample(ref.id, kind: ref.kind),
            tagline: "Accroche",
            genres: [TitleGenre(id: 28, name: "Action")],
            runtimeMinutes: 112,
            seasonsCount: seasons.isEmpty ? nil : seasons.count,
            episodesCount: nil,
            seasons: seasons,
            cast: [CastPerson(name: "Camille Martin", character: "Léa", profileURL: nil)],
            directors: ["Sam Durand"],
            trailerURL: URL(string: "https://www.youtube.com/watch?v=abc"),
            similar: [.sample(ref.id + 1000, kind: ref.kind)],
            stale: stale
        )
    }
}

extension SeasonEpisodes {
    static func sample(_ number: Int, stale: Bool = false) -> SeasonEpisodes {
        SeasonEpisodes(
            number: number,
            name: "Saison \(number)",
            overview: "",
            episodes: [
                EpisodeEntry(number: 1, name: "Pilote", overview: "", airDate: "2019-04-12", runtimeMinutes: 45, stillURL: nil, rating: 8),
                EpisodeEntry(number: 2, name: "Suite", overview: "", airDate: nil, runtimeMinutes: nil, stillURL: nil, rating: 0),
            ],
            stale: stale
        )
    }
}

// MARK: Doublure du catalogue

struct TitlesCall: Equatable, Sendable {
    let kind: TitleKind
    let genre: Int
    let page: Int
}

struct SearchCall: Equatable, Sendable {
    let query: String
    let page: Int
}

struct SeasonCall: Equatable, Sendable {
    let series: Int
    let number: Int
}

/// Doublure du catalogue, sans toucher au cœur natif. Les réponses se règlent
/// test par test ; les appels reçus sont notés (au moment où ils arrivent,
/// avant d'attendre la réponse).
actor FakeCatalogClient: CatalogClient {
    typealias ShelfHandler = @Sendable (DiscoverShelf, Int) async throws -> TitleList
    typealias TitlesHandler = @Sendable (TitleKind, Int, Int) async throws -> TitleList
    typealias SearchHandler = @Sendable (String, Int) async throws -> TitleList
    typealias SheetHandler = @Sendable (TitleRef) async throws -> TitleSheet
    typealias SeasonHandler = @Sendable (Int, Int) async throws -> SeasonEpisodes
    typealias GenresHandler = @Sendable (TitleKind) async throws -> [TitleGenre]

    private var shelfHandler: ShelfHandler = { shelf, _ in
        let index = DiscoverShelf.allCases.firstIndex(of: shelf) ?? 0
        return .sample([index * 10 + 1, index * 10 + 2])
    }
    private var genresHandler: GenresHandler = { _ in
        [TitleGenre(id: 28, name: "Action"), TitleGenre(id: 35, name: "Comédie")]
    }
    private var titlesHandler: TitlesHandler = { _, _, _ in .sample([1, 2, 3]) }
    private var searchHandler: SearchHandler = { _, _ in .sample([1, 2, 3]) }
    private var sheetHandler: SheetHandler = { ref in .sample(ref) }
    private var seasonHandler: SeasonHandler = { _, number in .sample(number) }

    private(set) var shelfCalls: [DiscoverShelf] = []
    private(set) var genreCalls: [TitleKind] = []
    private(set) var titlesCalls: [TitlesCall] = []
    private(set) var searchCalls: [SearchCall] = []
    private(set) var sheetCalls: [TitleRef] = []
    private(set) var seasonCalls: [SeasonCall] = []

    func set(shelf: @escaping ShelfHandler) { shelfHandler = shelf }
    func set(genres: Result<[TitleGenre], CatalogFailure>) { genresHandler = { _ in try genres.get() } }
    func set(titles: @escaping TitlesHandler) { titlesHandler = titles }
    func set(search: @escaping SearchHandler) { searchHandler = search }
    func set(sheet: @escaping SheetHandler) { sheetHandler = sheet }
    func set(season: @escaping SeasonHandler) { seasonHandler = season }

    func shelf(_ shelf: DiscoverShelf, page: Int) async throws -> TitleList {
        shelfCalls.append(shelf)
        return try await shelfHandler(shelf, page)
    }

    func genres(of kind: TitleKind) async throws -> [TitleGenre] {
        genreCalls.append(kind)
        return try await genresHandler(kind)
    }

    func titles(of kind: TitleKind, genre: Int, page: Int) async throws -> TitleList {
        titlesCalls.append(TitlesCall(kind: kind, genre: genre, page: page))
        return try await titlesHandler(kind, genre, page)
    }

    func search(_ query: String, page: Int) async throws -> TitleList {
        searchCalls.append(SearchCall(query: query, page: page))
        return try await searchHandler(query, page)
    }

    func sheet(of ref: TitleRef) async throws -> TitleSheet {
        sheetCalls.append(ref)
        return try await sheetHandler(ref)
    }

    func season(of series: Int, number: Int) async throws -> SeasonEpisodes {
        seasonCalls.append(SeasonCall(series: series, number: number))
        return try await seasonHandler(series, number)
    }
}

/// Barrière à ouvrir à la main : fait attendre une doublure pour régler
/// l'ordre dans lequel les réponses arrivent.
actor Gate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        for waiter in waiters { waiter.resume() }
        waiters = []
    }
}
