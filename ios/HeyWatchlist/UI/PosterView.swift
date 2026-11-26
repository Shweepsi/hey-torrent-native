import SwiftUI

/// Image distante dont l'adresse vient du cœur (jamais construite ici).
///
/// `AsyncImage` passe par `URLSession.shared`, donc par `URLCache.shared`,
/// que l'app agrandit au lancement (`HeyWatchlistApp`) pour que les affiches
/// déjà vues reviennent du disque. Pas d'adresse, ou échec : un fond uni et
/// une icône, jamais un trou.
struct RemoteImage: View {
    let url: URL?
    var fallbackSymbol = "film"

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        AsyncImage(
            url: url,
            transaction: Transaction(animation: reduceMotion ? nil : .easeIn(duration: 0.2))
        ) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else if url == nil || phase.error != nil {
                Image(systemName: fallbackSymbol)
                    .font(.title2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.white.opacity(0.08))
            } else {
                Color.white.opacity(0.08)
            }
        }
    }
}

/// Affiche en 2:3, coins arrondis et filet clair. Aucun texte dessus.
struct PosterView: View {
    let url: URL?

    private let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)

    var body: some View {
        Color.clear
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .overlay { RemoteImage(url: url) }
            .clipShape(shape)
            .overlay { shape.strokeBorder(Color.white.opacity(0.14), lineWidth: 1) }
            .accessibilityHidden(true)
    }
}

/// Sert de départ au zoom vers la fiche ; `source` distingue deux vignettes du
/// même titre présentes à l'écran (deux rangées, par exemple).
struct TitleRoute: Hashable {
    let title: Title
    let source: String

    init(_ title: Title, in context: String) {
        self.title = title
        source = "\(context).\(title.kind).\(title.ref.id)"
    }
}

/// Affiche, nom et « Film · 2024 » : touchée, elle ouvre la fiche. Le texte est
/// sous l'affiche, jamais dessus (docs/DESIGN.md, règle 3).
struct PosterCard: View {
    let title: Title
    let context: String
    let zoom: Namespace.ID

    var body: some View {
        let route = TitleRoute(title, in: context)
        NavigationLink(value: route) {
            VStack(alignment: .leading, spacing: 8) {
                PosterView(url: title.posterURL)
                    .matchedTransitionSource(id: route.source, in: zoom)
                Text(verbatim: title.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(verbatim: title.caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Rangée horizontale d'affiches.
struct PosterRow: View {
    static let cardWidth: CGFloat = 124

    let titles: [Title]
    let context: String
    let zoom: Namespace.ID

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(titles) { title in
                    PosterCard(title: title, context: context, zoom: zoom)
                        .frame(width: Self.cardWidth)
                }
            }
            .padding(.horizontal, 20)
        }
        .scrollIndicators(.hidden)
    }
}
