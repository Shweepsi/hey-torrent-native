import Foundation

/// Échec d'un appel au cœur, ramené à ce que l'interface sait expliquer.
///
/// Le cœur ne produit aucun texte affiché : il renvoie un *type* d'erreur
/// (`CoreError`), traduit ici en français via `Localizable.xcstrings`.
/// Le texte brut de l'erreur n'est jamais affiché ni journalisé.
enum CoreFailure: Equatable, Sendable {
    /// Défaillance interne du cœur (`CoreError.Internal`).
    case internalError
    /// Toute autre erreur : panique rattrapée par UniFFI, liaisons incohérentes…
    case unexpected

    init(_ error: any Error) {
        switch error as? CoreError {
        case .Internal?:
            self = .internalError
        case nil:
            self = .unexpected
        }
    }

    /// Message français à afficher.
    var message: LocalizedStringResource {
        switch self {
        case .internalError: "core.error.internal"
        case .unexpected: "core.error.unexpected"
        }
    }
}
