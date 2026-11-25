/// Ce que l'interface sait du cœur Rust : de simples chaînes à afficher.
struct CoreStatus: Equatable, Sendable {
    /// Version du cœur (`core_version()`), identique à celle de l'app.
    let version: String
    /// Réponse de l'appel synchrone `ping()`.
    let pong: String
    /// Réponse de l'appel asynchrone `ping_async()`.
    let asyncPong: String
}

/// Seul point d'entrée de l'interface vers le cœur.
///
/// Les appels se font hors du fil principal ; l'implémentation réelle
/// (`LiveCoreClient`) parle à Rust, les tests utilisent une doublure.
protocol CoreClient: Sendable {
    func status() async throws -> CoreStatus
}
