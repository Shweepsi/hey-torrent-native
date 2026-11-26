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

    init(model: AccountModel, state: AccountModel.SignedIn, catalog: any CatalogClient) {
        self.model = model
        self.state = state
        self.catalog = catalog
        _discover = State(initialValue: DiscoverModel(client: catalog))
        _search = State(initialValue: SearchModel(client: catalog))
    }

    var body: some View {
        TabView(selection: $selection) {
            Tab("tab.discover", systemImage: "sparkles", value: AppTab.discover) {
                DiscoverView(model: discover, catalog: catalog)
            }
            Tab("tab.watchlist", systemImage: "bookmark", value: AppTab.watchlist) {
                ComingSoonView(title: "tab.watchlist", message: "watchlist.comingSoon")
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
    }
}

/// Onglet pas encore construit (jalon 4).
private struct ComingSoonView: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey

    var body: some View {
        NavigationStack {
            ZStack {
                HeyBackground()
                Text(message)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(24)
                    .glassEffect(.regular, in: .rect(cornerRadius: 28))
                    .padding(24)
            }
            .navigationTitle(title)
        }
    }
}
