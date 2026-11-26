import Foundation
import Observation

/// Watchlist de l'utilisateur, partagée par l'onglet Watchlist et les fiches :
/// un ajout se voit partout. Le réseau n'est jamais un échec : le cœur met les
/// changements en attente et `offline` passe à vrai.
@MainActor
@Observable
final class WatchlistModel {
    private(set) var entries: [WatchlistEntry] = []
    /// La copie locale a été lue au moins une fois.
    private(set) var loaded = false
    private(set) var refreshing = false
    private(set) var offline = false
    /// Titres dont un changement part.
    private(set) var busy: Set<TitleRef> = []
    /// Échec à montrer une fois, puis remis à `nil` par la vue.
    var message: WatchlistFailure?

    private let client: any WatchlistClient

    init(client: any WatchlistClient) {
        self.client = client
    }

    func entry(for ref: TitleRef) -> WatchlistEntry? {
        entries.first { $0.ref == ref }
    }

    /// À l'arrivée sur les onglets : la copie locale tout de suite, puis la synchronisation.
    func open() async {
        await finishingEvenIfCancelled {
            if let local = await self.attempt({ try await self.client.entries() }) {
                self.entries = local
            }
            self.loaded = true
            await self.synchronize()
        }
    }

    /// Tirer pour rafraîchir.
    func refresh() async {
        await finishingEvenIfCancelled { await self.synchronize() }
    }

    /// `seasons` : `nil` pour un film ou une série entière.
    func add(_ title: Title, seasons: [Int]?) async {
        await change(title.ref) { try await self.client.add(title, seasons: seasons) }
    }

    func remove(_ ref: TitleRef) async {
        await change(ref) { try await self.client.remove(ref) }
    }

    private func synchronize() async {
        guard !refreshing else { return }
        refreshing = true
        if let sync = await attempt({ try await self.client.refresh() }) {
            apply(sync)
        }
        refreshing = false
    }

    private func change(_ ref: TitleRef, _ operation: @escaping @MainActor () async throws -> WatchlistSync) async {
        guard !busy.contains(ref) else { return }
        busy.insert(ref)
        await finishingEvenIfCancelled {
            if let sync = await self.attempt(operation) {
                self.apply(sync)
            } else if let local = await self.attempt({ try await self.client.entries() }) {
                // Un refus laisse la copie locale sans le titre : on la relit.
                self.entries = local
            }
            self.busy.remove(ref)
        }
    }

    private func apply(_ sync: WatchlistSync) {
        entries = sync.entries
        offline = sync.offline
        loaded = true
    }

    /// Exécute `operation` ; un échec est rangé dans `message` et rend `nil`.
    private func attempt<T>(_ operation: @MainActor () async throws -> T) async -> T? {
        do {
            return try await operation()
        } catch {
            message = WatchlistFailure(error)
            return nil
        }
    }
}
