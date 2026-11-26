import Foundation
import Observation

/// Liste de titres à défilement infini (recherche, genre) : première page,
/// pages suivantes quand la fin approche, réponses obsolètes ignorées.
///
/// Chaque `begin` ou `reset` ouvre une nouvelle « génération » : une réponse
/// qui arrive après coup (requête remplacée, changement de type) n'est jamais
/// affichée. C'est aussi ce qui écarte la réponse d'une tâche annulée : l'appel
/// au cœur, lui, va toujours à son terme (voir `LiveCatalogClient`).
@MainActor
@Observable
final class TitlePager {
    enum Phase: Equatable, Sendable {
        /// Rien à montrer (pas de requête).
        case idle
        case loading
        case loaded
        case failed(CatalogFailure)
    }

    /// Charge une page (à partir de 1).
    typealias Fetch = @Sendable (_ page: Int) async throws -> TitleList

    /// Nombre d'affiches avant la fin à partir duquel la page suivante part.
    private static let prefetchMargin = 6

    private(set) var phase: Phase = .idle
    private(set) var items: [Title] = []
    private(set) var page = 0
    private(set) var totalPages = 0
    private(set) var isLoadingMore = false
    /// Échec du chargement de la page suivante : les titres déjà là restent.
    private(set) var moreFailure: CatalogFailure?
    /// Au moins une page vient du cache faute de réseau.
    private(set) var isStale = false

    @ObservationIgnored private var seen: Set<TitleRef> = []
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var fetch: Fetch?
    @ObservationIgnored private var firstPage: Task<Void, Never>?
    /// Chargement de la page suivante en cours (utile aux tests).
    @ObservationIgnored private(set) var nextPage: Task<Void, Never>?

    var hasMore: Bool { page < totalPages }

    /// Annule ce qui est en cours et vide la liste. `keepingItems` (avec
    /// `loading`) : les titres déjà là restent, l'interface les estompe, jusqu'à
    /// l'arrivée de la première page de la requête suivante, qui les remplace.
    func reset(loading: Bool = false, keepingItems: Bool = false) {
        generation += 1
        firstPage?.cancel()
        firstPage = nil
        nextPage?.cancel()
        nextPage = nil
        fetch = nil
        isLoadingMore = false
        moreFailure = nil
        if !keepingItems { clearItems() }
        phase = loading ? .loading : .idle
    }

    /// Charge la première page. Le chargement va jusqu'au bout même si la
    /// tâche de la vue est annulée ; seul `reset` ou un nouveau `begin` l'annule.
    /// `keepingItems` : voir `reset`.
    func begin(keepingItems: Bool = false, _ fetch: @escaping Fetch) async {
        reset(loading: true, keepingItems: keepingItems)
        self.fetch = fetch
        let token = generation
        let task = Task { await self.loadFirstPage(token: token, using: fetch) }
        firstPage = task
        await task.value
    }

    /// Recommence après un échec de la première page.
    func retry() async {
        guard let fetch else { return }
        await begin(fetch)
    }

    /// À appeler quand une affiche apparaît : charge la page suivante si c'est
    /// l'une des dernières.
    func loadMoreIfNeeded(after title: Title) {
        guard phase == .loaded, hasMore, !isLoadingMore, moreFailure == nil,
              items.suffix(Self.prefetchMargin).contains(where: { $0.ref == title.ref })
        else { return }
        requestNextPage()
    }

    /// Recommence après un échec d'une page suivante.
    func retryMore() {
        guard phase == .loaded, hasMore, !isLoadingMore else { return }
        requestNextPage()
    }

    private func requestNextPage() {
        guard let fetch else { return }
        isLoadingMore = true
        moreFailure = nil
        let token = generation
        let wanted = page + 1
        nextPage = Task { await self.loadNextPage(wanted, token: token, using: fetch) }
    }

    private func loadFirstPage(token: Int, using fetch: Fetch) async {
        do {
            let list = try await fetch(1)
            guard token == generation else { return }
            // La première page remplace les titres que `reset` avait gardés.
            clearItems()
            append(list, page: 1)
            phase = .loaded
        } catch {
            guard token == generation else { return }
            clearItems()
            phase = .failed(CatalogFailure(error))
        }
    }

    private func loadNextPage(_ number: Int, token: Int, using fetch: Fetch) async {
        do {
            let list = try await fetch(number)
            guard token == generation else { return }
            append(list, page: number)
            isLoadingMore = false
        } catch {
            guard token == generation else { return }
            moreFailure = CatalogFailure(error)
            isLoadingMore = false
        }
    }

    private func clearItems() {
        items = []
        seen = []
        page = 0
        totalPages = 0
        isStale = false
    }

    /// Ajoute les titres pas encore vus : TMDB peut répéter un titre d'une
    /// page à l'autre quand les classements bougent.
    private func append(_ list: TitleList, page number: Int) {
        for title in list.items where !seen.contains(title.ref) {
            seen.insert(title.ref)
            items.append(title)
        }
        page = number
        totalPages = list.totalPages
        isStale = isStale || list.stale
    }
}
