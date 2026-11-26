import SwiftUI

/// Bloc qui pulse doucement, pour les contenus en cours de chargement. Ni flou
/// ni verre (docs/DESIGN.md, règle 5). Immobile si « Réduire les animations »
/// est actif.
struct SkeletonBlock: View {
    var cornerRadius: CGFloat = 14

    @State private var dimmed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(Color.white.opacity(dimmed ? 0.05 : 0.13))
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                    dimmed = true
                }
            }
            .accessibilityHidden(true)
    }
}

/// Une affiche et deux lignes de texte, en attente de contenu.
struct PosterSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SkeletonBlock(cornerRadius: 16)
                .aspectRatio(2.0 / 3.0, contentMode: .fit)
            SkeletonBlock(cornerRadius: 5).frame(height: 12)
            SkeletonBlock(cornerRadius: 5).frame(width: 56, height: 10)
        }
    }
}

/// Rangée d'affiches en attente de contenu.
struct PosterRowSkeleton: View {
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ForEach(0..<5, id: \.self) { _ in
                PosterSkeleton().frame(width: PosterRow.cardWidth)
            }
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("common.loading"))
    }
}

/// Carrousel en attente de contenu.
struct HeroSkeleton: View {
    var body: some View {
        SkeletonBlock(cornerRadius: 28)
            .aspectRatio(16.0 / 9.0, contentMode: .fit)
            .padding(.horizontal, 20)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("common.loading"))
    }
}

/// Lignes de texte en attente de contenu (synopsis, épisodes).
struct TextSkeleton: View {
    var lines = 3

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(0..<lines, id: \.self) { index in
                SkeletonBlock(cornerRadius: 5)
                    .frame(height: 12)
                    .padding(.trailing, index == lines - 1 ? 80 : 0)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("common.loading"))
    }
}

/// Bandeau discret : ce qui s'affiche vient de l'appareil faute de réseau.
/// Un fond uni, pas de verre : il défile avec le contenu.
struct StaleBanner: View {
    var body: some View {
        Label("catalog.stale", systemImage: "wifi.slash")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color.white.opacity(0.08), in: Capsule())
            .frame(maxWidth: .infinity)
    }
}

/// Échec d'un chargement : le message, et de quoi réessayer. Ne touche que sa
/// zone, le reste de l'écran continue de vivre.
struct FailureCard: View {
    let failure: CatalogFailure
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text(failure.message)
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("common.retry", action: retry)
                .buttonStyle(.glass)
                .controlSize(.large)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}
