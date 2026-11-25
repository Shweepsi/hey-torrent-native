import SwiftUI

/// Écran d'accueil du jalon 1 : affiche ce que renvoie le cœur Rust.
struct HomeView: View {
    let model: CoreStatusModel

    var body: some View {
        ZStack {
            HeyBackground()
            GeometryReader { proxy in
                ScrollView {
                    // `spacing` : distance sous laquelle deux verres fusionnent. Il reste
                    // inférieur à l'écart réel (24) pour que le titre et la carte restent distincts.
                    GlassEffectContainer(spacing: 12) {
                        VStack(spacing: 24) {
                            Text("app.name")
                                .font(.largeTitle.bold())
                                .accessibilityAddTraits(.isHeader)
                                .padding(.horizontal, 28)
                                .padding(.vertical, 14)
                                .glassEffect(
                                    .regular.tint(HeyTheme.primary.opacity(0.3)),
                                    in: .capsule
                                )
                            CoreStatusCard(model: model)
                        }
                    }
                    .padding(24)
                    // Centre le contenu quand il est court, défile quand il est long
                    // (grandes tailles de texte, orientation paysage).
                    .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        // Jalon 1 : seule la palette sombre existe (le thème clair viendra avec
        // docs/DESIGN.md). Le texte reste lisible quel que soit le réglage système.
        .preferredColorScheme(.dark)
        .task { await model.load() }
    }
}

/// Carte de verre : chargement, réponse du cœur ou erreur traduite.
private struct CoreStatusCard: View {
    let model: CoreStatusModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("home.core.title")
                .font(.headline)
                .foregroundStyle(.secondary)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(24)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            HStack(spacing: 12) {
                ProgressView()
                Text("home.core.loading")
            }
        case .loaded(let status):
            StatusRow(label: "home.core.version", value: status.version)
            StatusRow(label: "home.core.pong", value: status.pong)
            StatusRow(label: "home.core.pong.async", value: status.asyncPong)
        case .failed(let failure):
            Text(failure.message)
            Button("home.core.retry") {
                Task { await model.load() }
            }
            .buttonStyle(.glass)
        }
    }
}

/// Une ligne « libellé : valeur ». La valeur vient du cœur : affichée telle quelle.
private struct StatusRow: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(verbatim: value)
                .font(.title3.monospaced())
        }
        .accessibilityElement(children: .combine)
    }
}
