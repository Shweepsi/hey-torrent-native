/// Implémentation réelle : appelle les fonctions UniFFI du cœur Rust.
struct LiveCoreClient: CoreClient {
    // `@concurrent` : l'appel quitte toujours l'acteur principal, même si
    // l'appelant y est (règle du projet : jamais le cœur sur le fil principal).
    @concurrent
    func status() async throws -> CoreStatus {
        let version = coreVersion()
        let pong = ping()
        let asyncPong = try await pingAsync()
        return CoreStatus(version: version, pong: pong, asyncPong: asyncPong)
    }
}
