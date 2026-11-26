import SwiftUI

@main
struct HeyWatchlistApp: App {
    @State private var model: AccountModel
    private let catalog: any CatalogClient

    init() {
        // Affiches et fonds : `AsyncImage` lit et écrit dans `URLCache.shared`,
        // dont la taille par défaut (10 Mo sur disque) est vite saturée.
        URLCache.shared = URLCache(memoryCapacity: 48 * 1_024 * 1_024, diskCapacity: 256 * 1_024 * 1_024)

        // Le compte et le catalogue partagent le même `Account` du cœur.
        let source = CoreAccountSource()
        let model = AccountModel(client: LiveAccountClient(source: source))
        catalog = LiveCatalogClient(source: source, onSignedOut: { await model.sessionExpired() })
        _model = State(initialValue: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model, catalog: catalog)
        }
    }
}
