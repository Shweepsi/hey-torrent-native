import Foundation

/// Échec d'une lecture du catalogue, ramené à ce que l'interface sait expliquer.
///
/// Le cœur ne produit aucun texte affiché : il renvoie un *type* d'erreur
/// (`CatalogError`), traduit ici en français via `Localizable.xcstrings`.
/// Le texte brut de l'erreur n'est jamais affiché ni journalisé.
enum CatalogFailure: Error, Equatable, Sendable, CaseIterable {
    case network
    /// Session perdue (après le renouvellement automatique) : se reconnecter.
    case signedOut
    case notFound
    /// Le service du catalogue ne répond pas.
    case unavailable
    case server
    /// Adresse du serveur absente de cette compilation de l'app.
    case notConfigured
    case unexpected

    init(_ error: any Error) {
        if let failure = error as? CatalogFailure {
            self = failure
            return
        }
        switch error as? CatalogError {
        case .Network?: self = .network
        case .SignedOut?: self = .signedOut
        case .NotFound?: self = .notFound
        case .Unavailable?: self = .unavailable
        case .Server?: self = .server
        case .Unexpected?, .Internal?, nil: self = .unexpected
        }
    }

    /// Message français à afficher.
    var message: LocalizedStringResource {
        switch self {
        case .network: "catalog.error.network"
        case .signedOut: "catalog.error.signedOut"
        case .notFound: "catalog.error.notFound"
        case .unavailable: "catalog.error.unavailable"
        case .server: "catalog.error.server"
        case .notConfigured: "catalog.error.notConfigured"
        case .unexpected: "catalog.error.unexpected"
        }
    }
}
