import SwiftUI

/// Racine de l'interface : accueil tant qu'on n'est pas connecté, sinon les onglets.
struct RootView: View {
    let model: AccountModel

    var body: some View {
        ZStack {
            HeyBackground()
            switch model.state {
            case .starting:
                // Lecture locale très courte : on garde le décor seul.
                Color.clear
            case .signedOut(let state):
                WelcomeView(model: model, state: state)
            case .signedIn(let state):
                MainTabView(model: model, state: state)
            }
        }
        // Seule la palette sombre existe pour l'instant (docs/DESIGN.md).
        .preferredColorScheme(.dark)
        .task { await model.start() }
    }
}
