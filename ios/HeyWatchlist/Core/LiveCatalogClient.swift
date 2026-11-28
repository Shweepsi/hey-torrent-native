import Foundation

/// Implémentation réelle : appelle le catalogue UniFFI du cœur Rust.
///
/// Un acteur : les appels quittent toujours le fil principal. Le `Catalog`
/// est bâti sur l'unique `Account` du processus (`CoreAccountSource`), donc sur
/// la même session que le compte. Il est créé à la première lecture ; le cœur
/// n'ouvre son cache SQLite qu'à ce moment-là, lui aussi hors du fil principal.
actor LiveCatalogClient: CatalogClient {
    /// Fichier du cache hors ligne : dossier Caches de l'app, jamais sauvegardé.
    /// `nil` si le dossier est inutilisable (le cœur se passe alors de cache).
    static let defaultCachePath: String? = {
        guard let caches = try? FileManager.default.url(
            for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ) else { return nil }
        return caches.appending(path: "heywatchlist-catalog.sqlite").path(percentEncoded: false)
    }()

    private let source: CoreAccountSource
    private let cachePath: String?
    private let onSignedOut: @Sendable () async -> Void
    private var catalog: Catalog?

    /// `onSignedOut` : appelé quand le cœur constate que la session n'est plus
    /// valable (jeton de rafraîchissement refusé), pour revenir à l'accueil.
    init(
        source: CoreAccountSource,
        cachePath: String? = LiveCatalogClient.defaultCachePath,
        onSignedOut: @escaping @Sendable () async -> Void = {}
    ) {
        self.source = source
        self.cachePath = cachePath
        self.onSignedOut = onSignedOut
    }

    private func core() async throws -> Catalog {
        if let catalog { return catalog }
        let account: Account
        do {
            account = try await source.account()
        } catch AccountFailure.notConfigured {
            throw CatalogFailure.notConfigured
        } catch {
            throw CatalogFailure.unexpected
        }
        // Un autre appel a pu créer le catalogue pendant l'attente.
        if let catalog { return catalog }
        let created = Catalog(account: account, cachePath: cachePath)
        catalog = created
        return created
    }

    /// Exécute un appel au cœur et traduit ses erreurs. Une tâche annulée ne
    /// fait pas échouer l'appel : les liaisons UniFFI (0.32) ne regardent jamais
    /// l'annulation Swift, l'appel va à son terme et l'appelant ignore la réponse.
    private func call<T: Sendable>(_ body: (Catalog) async throws -> T) async throws -> T {
        let catalog = try await core()
        do {
            return try await body(catalog)
        } catch {
            let failure = CatalogFailure(error)
            if failure == .signedOut { await onSignedOut() }
            throw failure
        }
    }

    func shelf(_ shelf: DiscoverShelf, page: Int) async throws -> TitleList {
        try await call { try await TitleList(core: $0.shelf(shelf: shelf.core, page: UInt32(clamping: page))) }
    }

    func genres(of kind: TitleKind) async throws -> [TitleGenre] {
        try await call { try await $0.genres(mediaType: kind.core).map(TitleGenre.init(core:)) }
    }

    func titles(of kind: TitleKind, genre: Int, page: Int) async throws -> TitleList {
        try await call {
            try await TitleList(core: $0.byGenre(
                mediaType: kind.core,
                genreId: UInt32(clamping: genre),
                page: UInt32(clamping: page)
            ))
        }
    }

    func search(_ query: String, page: Int) async throws -> TitleList {
        try await call { try await TitleList(core: $0.search(query: query, page: UInt32(clamping: page))) }
    }

    func sheet(of ref: TitleRef) async throws -> TitleSheet {
        try await call { try await TitleSheet(core: $0.detail(mediaType: ref.kind.core, id: UInt64(clamping: ref.id))) }
    }

    func season(of series: Int, number: Int) async throws -> SeasonEpisodes {
        try await call {
            try await SeasonEpisodes(core: $0.season(
                tvId: UInt64(clamping: series),
                seasonNumber: UInt32(clamping: number)
            ))
        }
    }
}

// MARK: Traduction cœur vers domaine

extension URL {
    /// Adresse de lecture d'une vidéo YouTube, ouverte par le système.
    static func youtubeVideo(key: String) -> URL? {
        var components = URLComponents(string: "https://www.youtube.com/watch")
        components?.queryItems = [URLQueryItem(name: "v", value: key)]
        return components?.url
    }
}

extension TitleKind {
    init(core: MediaType) {
        switch core {
        case .movie: self = .movie
        case .tv: self = .series
        }
    }

    var core: MediaType {
        switch self {
        case .movie: .movie
        case .series: .tv
        }
    }
}

extension DiscoverShelf {
    var core: Shelf {
        switch self {
        case .trending: .trendingWeek
        case .nowPlaying: .nowPlayingMovies
        case .upcoming: .upcomingMovies
        case .popularMovies: .popularMovies
        case .popularSeries: .popularSeries
        case .onTheAirSeries: .onTheAirSeries
        case .topRatedMovies: .topRatedMovies
        case .topRatedSeries: .topRatedSeries
        }
    }
}

extension Title {
    init(core: TitleSummary) {
        self.init(
            ref: TitleRef(kind: TitleKind(core: core.mediaType), id: Int(clamping: core.id)),
            name: core.title,
            year: core.year.map { Int($0) },
            overview: core.overview,
            posterURL: core.posterUrl.flatMap { URL(string: $0) },
            backdropURL: core.backdropUrl.flatMap { URL(string: $0) },
            rating: core.rating,
            voteCount: Int(core.voteCount)
        )
    }
}

extension TitleList {
    init(core: TitlePage) {
        self.init(
            items: core.items.map(Title.init(core:)),
            totalPages: Int(core.totalPages),
            stale: core.stale,
            page: Int(core.page)
        )
    }
}

extension TitleGenre {
    init(core: Genre) {
        self.init(id: Int(core.id), name: core.name)
    }
}

extension CastPerson {
    init(core: CastMember) {
        self.init(
            name: core.name,
            character: core.character.flatMap { $0.isEmpty ? nil : $0 },
            profileURL: core.profileUrl.flatMap { URL(string: $0) }
        )
    }
}

extension WatchProvider {
    init(core: Provider) {
        self.init(id: Int(core.id), name: core.name, logoURL: core.logoUrl.flatMap { URL(string: $0) })
    }
}

extension WatchProviders {
    init(core: Providers) {
        self.init(
            flatrate: core.flatrate.map(WatchProvider.init(core:)),
            rent: core.rent.map(WatchProvider.init(core:)),
            buy: core.buy.map(WatchProvider.init(core:))
        )
    }
}

extension SeasonEntry {
    init(core: SeasonSummary) {
        self.init(
            number: Int(core.number),
            name: core.name,
            episodeCount: Int(core.episodeCount),
            airDate: core.airDate,
            posterURL: core.posterUrl.flatMap { URL(string: $0) }
        )
    }
}

extension TitleSheet {
    init(core: TitleDetail) {
        // Le cœur range les bandes-annonces d'abord ; on ne compte pas dessus
        // pour le choix du bouton. À défaut de bande-annonce, un teaser ; un
        // extrait ou un making-of ne se fait pas passer pour elle.
        let trailer = core.videos.first { $0.kind == .trailer } ?? core.videos.first { $0.kind == .teaser }
        self.init(
            title: Title(core: core.summary),
            tagline: core.tagline.flatMap { $0.isEmpty ? nil : $0 },
            genres: core.genres.map(TitleGenre.init(core:)),
            runtimeMinutes: core.runtimeMinutes.map { Int($0) },
            seasonsCount: core.seasonsCount.map { Int($0) },
            episodesCount: core.episodesCount.map { Int($0) },
            seasons: core.seasons.map(SeasonEntry.init(core:)),
            cast: core.cast.map(CastPerson.init(core:)),
            directors: core.directors,
            trailerURL: trailer.flatMap { URL.youtubeVideo(key: $0.youtubeKey) },
            providers: WatchProviders(core: core.providers),
            similar: core.similar.map(Title.init(core:)),
            stale: core.stale
        )
    }
}

extension EpisodeEntry {
    init(core: Episode) {
        self.init(
            number: Int(core.number),
            name: core.name,
            overview: core.overview,
            airDate: core.airDate,
            runtimeMinutes: core.runtimeMinutes.map { Int($0) },
            stillURL: core.stillUrl.flatMap { URL(string: $0) },
            rating: core.rating
        )
    }
}

extension SeasonEpisodes {
    init(core: SeasonDetail) {
        self.init(
            number: Int(core.number),
            name: core.name,
            overview: core.overview,
            episodes: core.episodes.map(EpisodeEntry.init(core:)),
            stale: core.stale
        )
    }
}
