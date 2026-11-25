@testable import HeyWatchlist

/// Valeur de référence partagée par les tests.
extension CoreStatus {
    static let sample = CoreStatus(version: "0.1.0", pong: "pong", asyncPong: "pong")
}

/// Erreur étrangère au cœur, pour vérifier le cas « erreur inattendue ».
struct UnrelatedError: Error {}

/// Doublure qui répond dans l'ordre aux appels successifs, sans toucher au cœur natif.
actor FakeCoreClient: CoreClient {
    private var results: [Result<CoreStatus, any Error>]
    private(set) var callCount = 0

    init(results: [Result<CoreStatus, any Error>]) {
        self.results = results
    }

    func status() async throws -> CoreStatus {
        callCount += 1
        guard !results.isEmpty else { throw UnrelatedError() }
        return try results.removeFirst().get()
    }
}

/// Doublure qui reste en attente jusqu'à `release(_:)`, pour observer l'état
/// « chargement » pendant que le cœur n'a pas encore répondu.
actor GatedCoreClient: CoreClient {
    private var pending: CheckedContinuation<CoreStatus, any Error>?
    private var requestWaiter: CheckedContinuation<Void, Never>?
    private var requested = false

    func status() async throws -> CoreStatus {
        requested = true
        requestWaiter?.resume()
        requestWaiter = nil
        // Le corps de la continuation s'exécute sur l'acteur, avant tout autre
        // appel : `pending` est posé quand `release(_:)` passe.
        return try await withCheckedThrowingContinuation { pending = $0 }
    }

    /// Rend la main une fois que le modèle a bien appelé `status()`.
    func waitUntilRequested() async {
        if requested { return }
        await withCheckedContinuation { requestWaiter = $0 }
    }

    func release(_ result: Result<CoreStatus, any Error>) {
        pending?.resume(with: result)
        pending = nil
    }
}
