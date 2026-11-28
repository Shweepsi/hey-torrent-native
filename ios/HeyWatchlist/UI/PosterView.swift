import SwiftUI
import UIKit

/// Images déjà décodées, gardées en mémoire : une affiche qui revient à l'écran
/// (défilement, changement d'onglet, retour d'une fiche) s'affiche dès la
/// première image, sans repasser par le fond uni puis un fondu (clignotement).
@MainActor
private enum ImageMemory {
    static let cache: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.totalCostLimit = 96 * 1024 * 1024
        return cache
    }()
}

/// Image distante dont l'adresse vient du cœur (jamais construite ici).
///
/// Lue par `URLSession.shared`, donc par `URLCache.shared`, que l'app agrandit
/// au lancement (`HeyWatchlistApp`) pour que les affiches déjà vues reviennent
/// du disque ; puis gardée décodée en mémoire. Seule une image qui arrive du
/// réseau ou du disque apparaît en fondu. Pas d'adresse, ou échec : un fond uni
/// et une icône, jamais un trou.
struct RemoteImage: View {
    let url: URL?
    var fallbackSymbol = "film"

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var loaded: (url: URL, image: UIImage)?
    @State private var failed: URL?

    var body: some View {
        ZStack {
            if let image = current {
                Image(uiImage: image).resizable().scaledToFill()
            } else if url == nil || failed == url {
                Image(systemName: fallbackSymbol)
                    .font(.title2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.white.opacity(0.08))
            } else {
                Color.white.opacity(0.08)
            }
        }
        .task(id: url) { await load() }
    }

    /// L'image de l'adresse actuelle, si elle est déjà là.
    private var current: UIImage? {
        guard let url else { return nil }
        if let loaded, loaded.url == url { return loaded.image }
        return ImageMemory.cache.object(forKey: url as NSURL)
    }

    private func load() async {
        guard let url, current == nil else { return }
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let decoded = UIImage(data: data) else {
                failed = url
                return
            }
            // Décodée hors du fil principal : le défilement ne saccade pas.
            let image = await decoded.byPreparingForDisplay() ?? decoded
            guard !Task.isCancelled else { return }
            let cost = Int(image.size.width * image.size.height * image.scale * image.scale) * 4
            ImageMemory.cache.setObject(image, forKey: url as NSURL, cost: cost)
            withAnimation(reduceMotion ? nil : .easeIn(duration: 0.2)) {
                loaded = (url, image)
            }
        } catch {
            // Vue quittée avant la fin : rien à signaler.
            if !Task.isCancelled { failed = url }
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
                    .lineLimit(2, reservesSpace: true)
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
