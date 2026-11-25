import SwiftUI

/// Les trois onglets : Découvrir, Watchlist, Réglages (barre Liquid Glass du système).
struct MainTabView: View {
    let model: AccountModel
    let state: AccountModel.SignedIn

    var body: some View {
        TabView {
            Tab("tab.discover", systemImage: "sparkles") {
                ComingSoonView(title: "tab.discover", message: "discover.comingSoon")
            }
            Tab("tab.watchlist", systemImage: "bookmark") {
                ComingSoonView(title: "tab.watchlist", message: "watchlist.comingSoon")
            }
            Tab("tab.settings", systemImage: "gearshape") {
                SettingsView(model: model, state: state)
            }
        }
    }
}

/// Onglet pas encore construit (jalons 3 et 4).
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
