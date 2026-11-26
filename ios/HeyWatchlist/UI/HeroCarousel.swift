import SwiftUI

/// Carrousel de tête de Découvrir : les premiers titres tendance, avec leur
/// fond d'écran. Le texte repose sur une bande sombre presque opaque, jamais
/// sur l'image.
struct HeroCarousel: View {
    let titles: [Title]
    let zoom: Namespace.ID

    @State private var current: TitleRef?

    var body: some View {
        VStack(spacing: 12) {
            ScrollView(.horizontal) {
                LazyHStack(spacing: 12) {
                    ForEach(titles) { title in
                        HeroCard(title: title, zoom: zoom)
                            .containerRelativeFrame(.horizontal) { length, _ in length * 0.9 }
                            .id(title.ref)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $current)
            .scrollIndicators(.hidden)
            .contentMargins(.horizontal, 20, for: .scrollContent)

            HStack(spacing: 6) {
                ForEach(titles) { title in
                    Circle()
                        .fill(Color.white.opacity(title.ref == (current ?? titles.first?.ref) ? 0.95 : 0.3))
                        .frame(width: 7, height: 7)
                }
            }
            .accessibilityHidden(true)
        }
    }
}

private struct HeroCard: View {
    let title: Title
    let zoom: Namespace.ID

    private let shape = RoundedRectangle(cornerRadius: 28, style: .continuous)

    /// Voile sombre sous le texte. À 85 % ou plus, le blanc du titre garde
    /// plus de 13:1 et le sous-titre (gris à 60 %) plus de 5:1 même sur un fond
    /// d'écran entièrement blanc : DESIGN.md, règle 3 (3:1 pour le gros texte,
    /// 4,5:1 sinon). Calcul WCAG, sans rendu : à contrôler sur une image claire.
    private static let scrim = HeyTheme.background.opacity(0.88)

    var body: some View {
        let route = TitleRoute(title, in: "hero")
        NavigationLink(value: route) {
            Color.clear
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
                .overlay { RemoteImage(url: title.backdropURL) }
                .overlay(alignment: .bottom) { caption }
                .clipShape(shape)
                .overlay { shape.strokeBorder(Color.white.opacity(0.16), lineWidth: 1) }
                .matchedTransitionSource(id: route.source, in: zoom)
                .contentShape(shape)
        }
        .buttonStyle(.plain)
    }

    /// Un fondu vers une bande presque opaque, puis le texte posé sur la bande :
    /// la bande suit la taille du texte (Dynamic Type), pas une fraction de la
    /// carte, donc le texte n'est jamais sur une image à peine assombrie.
    private var caption: some View {
        VStack(spacing: 0) {
            LinearGradient(
                colors: [HeyTheme.background.opacity(0), Self.scrim],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: title.name)
                    .font(.title2.bold())
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(verbatim: title.caption)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Self.scrim)
        }
    }
}
