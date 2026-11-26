import SwiftUI

/// Onglets de la barre : le dernier (recherche) s'affiche à part, dans sa bulle.
private enum AppTab: Hashable {
    case discover
    case watchlist
    case settings
    case search
}

/// Découvrir, Watchlist, Réglages et la recherche (barre Liquid Glass du système).
struct MainTabView: View {
    let model: AccountModel
    let state: AccountModel.SignedIn
    let catalog: any CatalogClient

    @State private var selection = AppTab.discover
    /// Gardés ici, au-dessus des onglets : leurs données survivent aux changements d'onglet.
    @State private var discover: DiscoverModel
    @State private var search: SearchModel
    /// Partagée par l'onglet Watchlist et les fiches de tous les onglets.
    @State private var watchlist: WatchlistModel

    init(model: AccountModel, state: AccountModel.SignedIn, catalog: any CatalogClient, watchlist: any WatchlistClient) {
        self.model = model
        self.state = state
        self.catalog = catalog
        _discover = State(initialValue: DiscoverModel(client: catalog))
        _search = State(initialValue: SearchModel(client: catalog))
        _watchlist = State(initialValue: WatchlistModel(client: watchlist))
    }

    var body: some View {
        TabView(selection: $selection) {
            Tab("tab.discover", systemImage: "sparkles", value: AppTab.discover) {
                DiscoverView(model: discover, catalog: catalog)
            }
            Tab("tab.watchlist", systemImage: "bookmark", value: AppTab.watchlist) {
                WatchlistView(catalog: catalog)
            }
            Tab("tab.settings", systemImage: "gearshape", value: AppTab.settings) {
                SettingsView(model: model, state: state)
            }
            Tab("tab.search", systemImage: "magnifyingglass", value: AppTab.search, role: .search) {
                SearchView(model: search, catalog: catalog)
            }
        }
        // La barre se réduit quand on fait défiler vers le bas.
        .tabBarMinimizeBehavior(.onScrollDown)
        .environment(watchlist)
        // À chaque arrivée sur les onglets : copie locale, puis synchronisation.
        .task { await watchlist.open() }
        .alert(
            Text("watchlist.error.title"),
            isPresented: Binding(
                // La session expirée ramène déjà à l'accueil : pas d'alerte en plus.
                get: { watchlist.message != nil && watchlist.message != .signedOut },
                set: { shown in if !shown { watchlist.message = nil } }
            ),
            presenting: watchlist.message
        ) { _ in
            Button("common.ok") { watchlist.message = nil }
        } message: { failure in
            Text(failure.message)
        }
    }
}
