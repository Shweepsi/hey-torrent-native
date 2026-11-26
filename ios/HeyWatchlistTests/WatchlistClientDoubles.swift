import Foundation

@testable import HeyWatchlist

extension WatchlistEntry {
    static func sample(
        _ id: Int,
        kind: TitleKind = .movie,
        state: RequestState = .requested,
        seasons: [Int]? = nil,
        pending: Bool = false,
        refusalReason: String? = nil
    ) -> WatchlistEntry {
        WatchlistEntry(
            ref: TitleRef(kind: kind, id: id),
            name: "Titre \(id)",
            year: 2020,
            posterURL: nil,
            seasons: seasons,
            addedAt: Date(timeIntervalSince1970: 1_800_000_000),
            state: state,
            refusalReason: refusalReason,
            pending: pending
        )
    }
}

/// Doublure de la watchlist : une liste en mémoire. `failure` fait échouer
/// l'opération suivante ; `offline` marque les résultats hors ligne.
actor FakeWatchlistClient: WatchlistClient {
    private(set) var stored: [WatchlistEntry] = []
    private(set) var calls: [String] = []
    private var offline = false
    private var failure: WatchlistFailure?

    func set(stored: [WatchlistEntry]) { self.stored = stored }
    func set(offline: Bool) { self.offline = offline }
    func fail(next failure: WatchlistFailure) { self.failure = failure }

    private func failIfAsked() throws {
        if let failure {
            self.failure = nil
            throw failure
        }
    }

    private var sync: WatchlistSync { WatchlistSync(entries: stored, offline: offline) }

    func entries() async throws -> [WatchlistEntry] {
        calls.append("entries")
        return stored
    }

    func refresh() async throws -> WatchlistSync {
        calls.append("refresh")
        try failIfAsked()
        return sync
    }

    func add(_ title: Title, seasons: [Int]?) async throws -> WatchlistSync {
        calls.append("add \(title.ref.id) \(seasons.map { "\($0)" } ?? "entière")")
        try failIfAsked()
        stored.removeAll { $0.ref == title.ref }
        stored.insert(.sample(title.ref.id, kind: title.kind, seasons: seasons, pending: offline), at: 0)
        return sync
    }

    func remove(_ ref: TitleRef) async throws -> WatchlistSync {
        calls.append("remove \(ref.id)")
        try failIfAsked()
        stored.removeAll { $0.ref == ref }
        return sync
    }
}
