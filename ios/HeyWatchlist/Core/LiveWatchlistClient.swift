import Foundation

/// Implémentation réelle : appelle la watchlist UniFFI du cœur Rust, bâtie sur
/// l'unique `Account` du processus. Un acteur : les appels quittent toujours
/// le fil principal.
actor LiveWatchlistClient: WatchlistClient {
    /// Copie locale et file d'attente hors ligne : dossier Application Support
    /// (gardé, contrairement aux caches), exclu de la sauvegarde iCloud.
    static let defaultStorePath: String? = {
        guard var folder = try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ) else { return nil }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
        return folder.appending(path: "heywatchlist-watchlist.sqlite").path(percentEncoded: false)
    }()

    private let source: CoreAccountSource
    private let storePath: String?
    private let onSignedOut: @Sendable () async -> Void
    private var watchlist: Watchlist?

    init(
        source: CoreAccountSource,
        storePath: String? = LiveWatchlistClient.defaultStorePath,
        onSignedOut: @escaping @Sendable () async -> Void = {}
    ) {
        self.source = source
        self.storePath = storePath
        self.onSignedOut = onSignedOut
    }

    private func core() async throws -> Watchlist {
        if let watchlist { return watchlist }
        let account: Account
        do {
            account = try await source.account()
        } catch AccountFailure.notConfigured {
            throw WatchlistFailure.notConfigured
        } catch {
            throw WatchlistFailure.unexpected
        }
        if let watchlist { return watchlist }
        let created = Watchlist(account: account, storePath: storePath)
        watchlist = created
        return created
    }

    private func call<T: Sendable>(_ body: (Watchlist) async throws -> T) async throws -> T {
        let watchlist = try await core()
        do {
            return try await body(watchlist)
        } catch {
            let failure = WatchlistFailure(error)
            if failure == .signedOut { await onSignedOut() }
            throw failure
        }
    }

    func entries() async throws -> [WatchlistEntry] {
        try await call { await $0.items().map(WatchlistEntry.init(core:)) }
    }

    func refresh() async throws -> WatchlistSync {
        try await call { try await WatchlistSync(core: $0.refresh()) }
    }

    func add(_ title: Title, seasons: [Int]?) async throws -> WatchlistSync {
        let summary = TitleSummary(title: title)
        let numbers = seasons?.map { UInt32(clamping: $0) }
        return try await call { try await WatchlistSync(core: $0.add(title: summary, seasons: numbers)) }
    }

    func remove(_ ref: TitleRef) async throws -> WatchlistSync {
        try await call {
            try await WatchlistSync(core: $0.remove(mediaType: ref.kind.core, tmdbId: UInt64(clamping: ref.id)))
        }
    }
}

// MARK: Traduction entre le cœur et le domaine

extension TitleSummary {
    /// Ce que le cœur garde d'un titre ajouté avant la réponse du serveur
    /// (nom, année, affiche) ; le serveur relit le reste chez TMDB.
    init(title: Title) {
        self.init(
            id: UInt64(clamping: title.ref.id),
            mediaType: title.kind.core,
            title: title.name,
            year: title.year.map { UInt32(clamping: $0) },
            releaseDate: nil,
            overview: title.overview,
            posterUrl: title.posterURL?.absoluteString,
            backdropUrl: title.backdropURL?.absoluteString,
            rating: title.rating,
            voteCount: UInt32(clamping: title.voteCount),
            genreIds: []
        )
    }
}

extension RequestState {
    init(core: RequestStatus) {
        switch core {
        case .requested: self = .requested
        case .inProgress: self = .inProgress
        case .available: self = .available
        case .refused: self = .refused
        }
    }
}

extension WatchlistEntry {
    init(core: WatchlistItem) {
        self.init(
            ref: TitleRef(kind: TitleKind(core: core.mediaType), id: Int(clamping: core.tmdbId)),
            name: core.title,
            year: core.year.map { Int($0) },
            posterURL: core.posterUrl.flatMap { URL(string: $0) },
            seasons: core.seasons.map { $0.map { Int($0) } },
            addedAt: Date(timeIntervalSince1970: TimeInterval(core.addedAt)),
            state: RequestState(core: core.status),
            refusalReason: core.refusalReason,
            pending: core.pending
        )
    }
}

extension WatchlistSync {
    init(core: WatchlistSnapshot) {
        self.init(entries: core.items.map(WatchlistEntry.init(core:)), offline: core.offline)
    }
}
