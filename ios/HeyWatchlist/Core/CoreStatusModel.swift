import Observation

/// État de l'écran d'accueil : ce que répond le cœur Rust.
@MainActor
@Observable
final class CoreStatusModel {
    enum State: Equatable, Sendable {
        case loading
        case loaded(CoreStatus)
        case failed(CoreFailure)
    }

    private(set) var state: State = .loading

    private let client: any CoreClient

    init(client: any CoreClient) {
        self.client = client
    }

    /// Interroge le cœur. L'appel s'exécute hors du fil principal ; seule la
    /// mise à jour de `state` repasse par l'acteur principal.
    func load() async {
        state = .loading
        do {
            state = .loaded(try await client.status())
        } catch {
            // Vue disparue pendant l'appel : l'état reste « chargement », le
            // prochain `load()` le renouvellera.
            guard !Task.isCancelled else { return }
            state = .failed(CoreFailure(error))
        }
    }
}
