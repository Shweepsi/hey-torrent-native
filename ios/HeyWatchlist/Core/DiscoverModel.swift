import Foundation
import Observation

/// État de l'écran Découvrir : une rangée par liste du catalogue, plus les
/// genres. Chaque rangée se charge seule : l'échec de l'une ne bloque pas les
/// autres. Le modèle vit aussi longtemps que les onglets, donc les données
/// survivent au changement d'onglet.
@MainActor
@Observable
final class DiscoverModel {
    /// Nombre de titres du carrousel.
    static let heroCount = 6

    private(set) var shelves: [DiscoverShelf: Loadable<TitleList>] = Dictionary(
        uniqueKeysWithValues: DiscoverShelf.allCases.map { ($0, Loadable<TitleList>.loading) }
    )
    private(set) var genres: Loadable<[TitleGenre]> = .loading

    private let client: any CatalogClient
    @ObservationIgnored private var started = false

    init(client: any CatalogClient) {
        self.client = client
    }

    func state(of shelf: DiscoverShelf) -> Loadable<TitleList> {
        shelves[shelf] ?? .loading
    }

    /// Les premiers titres des tendances qui ont un fond d'écran.
    var heroTitles: [Title] {
        guard let list = state(of: .trending).value else { return [] }
        return Array(list.items.filter { $0.backdropURL != nil }.prefix(Self.heroCount))
    }

    /// Une rangée au moins vient du cache faute de réseau.
    var isStale: Bool {
        shelves.values.contains { $0.value?.stale == true }
    }

    /// Premier chargement. Les passages suivants (retour sur l'onglet) ne
    /// rechargent rien : on tire pour rafraîchir.
    func start() async {
        guard !started else { return }
        started = true
        await loadAll()
    }

    /// Tirer pour rafraîchir : ce qui est déjà affiché reste si le réseau manque.
    func refresh() async {
        started = true
        await loadAll()
    }

    func retry(_ shelf: DiscoverShelf) async {
        shelves[shelf] = .loading
        await finishingEvenIfCancelled { await self.load(shelf) }
    }

    func retryGenres() async {
        genres = .loading
        await finishingEvenIfCancelled { await self.loadGenres() }
    }

    private func loadAll() async {
        await finishingEvenIfCancelled {
            await withTaskGroup(of: Void.self) { group in
                for shelf in DiscoverShelf.allCases {
                    group.addTask { await self.load(shelf) }
                }
                group.addTask { await self.loadGenres() }
            }
        }
    }

    private func load(_ shelf: DiscoverShelf) async {
        do {
            shelves[shelf] = .loaded(try await client.shelf(shelf, page: 1))
        } catch {
            // Un rafraîchissement raté garde ce qui est déjà affiché.
            if shelves[shelf]?.value == nil {
                shelves[shelf] = .failed(CatalogFailure(error))
            }
        }
    }

    private func loadGenres() async {
        do {
            genres = .loaded(try await client.genres(of: .movie))
        } catch {
            if genres.value == nil {
                genres = .failed(CatalogFailure(error))
            }
        }
    }
}
