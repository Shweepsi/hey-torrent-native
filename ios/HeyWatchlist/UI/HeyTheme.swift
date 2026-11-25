import SwiftUI

/// Jetons visuels de HeyWatchlist (identiques à ceux de l'app Android).
///
/// Le verre lui-même vient du Liquid Glass d'iOS 26 (`glassEffect`) : on ne
/// recrée ni flou ni bordure à la main, on fournit seulement la palette.
enum HeyTheme {
    /// Bleu principal, aussi la couleur d'accent de l'app (`AccentColor`).
    static let primary = Color(hex: 0x4F9CF7)
    /// Fond sombre de l'app (aussi `Background` du catalogue, pour le lancement).
    static let background = Color(hex: 0x0C0C0F)
}

extension Color {
    /// Couleur sRGB opaque à partir d'une valeur 0xRRGGBB.
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Fond sombre dégradé, assez coloré pour que le verre se remarque.
struct HeyBackground: View {
    var body: some View {
        LinearGradient(
            colors: [HeyTheme.background, Color(hex: 0x0E1A33), HeyTheme.background],
            startPoint: .top,
            endPoint: .bottom
        )
        .overlay {
            RadialGradient(
                colors: [HeyTheme.primary.opacity(0.35), .clear],
                center: .topTrailing,
                startRadius: 0,
                endRadius: 460
            )
        }
        .ignoresSafeArea()
    }
}
