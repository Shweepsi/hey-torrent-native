import Foundation

/// Échec d'une opération de compte, ramené à ce que l'interface sait expliquer.
///
/// Le cœur ne produit aucun texte affiché : il renvoie un *type* d'erreur
/// (`AccountError`), traduit ici en français via `Localizable.xcstrings`.
/// Le texte brut de l'erreur n'est jamais affiché ni journalisé.
enum AccountFailure: Error, Equatable, Sendable {
    case network
    case invalidIdentity
    case invitationInvalid
    case signedOut
    case providerUnavailable
    case cancelled
    case server
    /// Adresse du serveur absente de cette compilation de l'app.
    case notConfigured
    case unexpected

    init(_ error: any Error) {
        if let failure = error as? AccountFailure {
            self = failure
            return
        }
        switch error as? AccountError {
        case .Network?: self = .network
        case .InvalidIdentity?: self = .invalidIdentity
        case .InvitationInvalid?: self = .invitationInvalid
        // Traitée à part par la connexion ; ailleurs, la session est perdue.
        case .InvitationRequired?, .SignedOut?: self = .signedOut
        case .ProviderUnavailable?: self = .providerUnavailable
        case .Cancelled?: self = .cancelled
        case .Server?: self = .server
        case .InvalidConfiguration?: self = .notConfigured
        case .Forbidden?, .Unexpected?, .Internal?, nil: self = .unexpected
        }
    }

    /// Message français à afficher.
    var message: LocalizedStringResource {
        switch self {
        case .network: "error.network"
        case .invalidIdentity: "error.invalidIdentity"
        case .invitationInvalid: "error.invitationInvalid"
        case .signedOut: "error.signedOut"
        case .providerUnavailable: "error.providerUnavailable"
        case .cancelled: "error.cancelled"
        case .server: "error.server"
        case .notConfigured: "error.notConfigured"
        case .unexpected: "error.unexpected"
        }
    }
}
