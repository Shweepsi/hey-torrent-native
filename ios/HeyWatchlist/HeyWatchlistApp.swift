import SwiftUI

@main
struct HeyWatchlistApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AccountModel
    private let catalog: any CatalogClient
    private let watchlist: any WatchlistClient

    init() {
        // Affiches et fonds : `AsyncImage` lit et écrit dans `URLCache.shared`,
        // dont la taille par défaut (10 Mo sur disque) est vite saturée.
        URLCache.shared = URLCache(memoryCapacity: 48 * 1_024 * 1_024, diskCapacity: 256 * 1_024 * 1_024)

        // Le compte, le catalogue et la watchlist partagent le même `Account` du cœur.
        let source = CoreAccountSource()
        let model = AccountModel(client: LiveAccountClient(source: source))
        catalog = LiveCatalogClient(source: source, onSignedOut: { await model.sessionExpired() })
        watchlist = LiveWatchlistClient(source: source, onSignedOut: { await model.sessionExpired() })
        PushCenter.shared.configure(source: source)
        _model = State(initialValue: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model, catalog: catalog, watchlist: watchlist)
        }
    }
}
