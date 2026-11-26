import Foundation

// Types de domaine de la watchlist. Ceux du cœur (WatchlistItem,
// RequestStatus…) ne sont connus que de `LiveWatchlistClient`.

/// État d'une demande, partagé par toutes les personnes qui l'attendent.
enum RequestState: Equatable, Sendable {
    case requested
    case inProgress
    case available
    case refused
}

/// Un titre de la watchlist et l'état de sa demande.
struct WatchlistEntry: Identifiable, Equatable, Sendable {
    let ref: TitleRef
    let name: String
    let year: Int?
    let posterURL: URL?
    /// Série : saisons demandées ; `nil` : film ou série entière.
    let seasons: [Int]?
    let addedAt: Date
    let state: RequestState
    /// Motif d'un refus, écrit par l'administrateur.
    let refusalReason: String?
    /// Changement fait sur ce téléphone, pas encore reçu par le serveur.
    let pending: Bool

    var id: TitleRef { ref }

    /// Le titre tel que la fiche l'attend ; elle complète le reste en se chargeant.
    var title: Title {
        Title(
            ref: ref, name: name, year: year, overview: "", posterURL: posterURL,
            backdropURL: nil, rating: 0, voteCount: 0
        )
    }
}

/// La watchlist après une synchronisation. `offline` : le serveur n'a pas
/// répondu, les changements attendent dans la file du cœur.
struct WatchlistSync: Equatable, Sendable {
    let entries: [WatchlistEntry]
    let offline: Bool
}

/// Échec d'une opération sur la watchlist. Le réseau n'en est jamais un : le
/// cœur garde les changements en attente.
enum WatchlistFailure: Error, Equatable, Sendable, CaseIterable {
    case signedOut
    /// Le serveur refuse ce titre ou ces saisons.
    case rejected
    case notConfigured
    case unexpected

    init(_ error: any Error) {
        if let failure = error as? WatchlistFailure {
            self = failure
            return
        }
        switch error as? WatchlistError {
        case .SignedOut?: self = .signedOut
        case .Rejected?: self = .rejected
        case .Internal?, nil: self = .unexpected
        }
    }

    var message: LocalizedStringResource {
        switch self {
        case .signedOut: "catalog.error.signedOut"
        case .rejected: "watchlist.error.rejected"
        case .notConfigured: "catalog.error.notConfigured"
        case .unexpected: "watchlist.error.unexpected"
        }
    }
}

/// Seul point d'entrée de l'interface vers la watchlist gérée par le cœur.
/// Chaque erreur levée est une `WatchlistFailure`.
protocol WatchlistClient: Sendable {
    /// Watchlist connue sur le téléphone, sans réseau.
    func entries() async throws -> [WatchlistEntry]
    /// Envoie les changements en attente et relit la watchlist du serveur.
    func refresh() async throws -> WatchlistSync
    /// `seasons` : `nil` pour un film ou une série entière.
    func add(_ title: Title, seasons: [Int]?) async throws -> WatchlistSync
    func remove(_ ref: TitleRef) async throws -> WatchlistSync
}
