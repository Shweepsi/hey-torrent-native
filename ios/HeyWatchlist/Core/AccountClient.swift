import Foundation

/// Accès à l'app, décidé par l'administrateur.
enum Access: Equatable, Sendable {
    case active
    /// Nouveau compte en attente de validation : écran d'attente.
    case pending
    /// Accès refusé ou retiré.
    case refused
}

/// Ce que l'interface affiche du compte connecté.
struct Profile: Equatable, Sendable {
    let firstName: String?
    let isAdmin: Bool
    /// Compte rattaché à Apple : la suppression demande une nouvelle autorisation Apple.
    let hasApple: Bool
    var access: Access = .active
}

/// Fournisseur d'identité. Distinct de l'`IdentityProvider` du cœur, compilé
/// dans le même module : seul `LiveAccountClient` connaît les deux.
enum SignInProvider: Equatable, Sendable {
    case apple
    case google
}

/// Jeton d'identité obtenu d'Apple ou de Google, à faire vérifier par le serveur.
struct IdentityCredential: Equatable, Sendable {
    let provider: SignInProvider
    let idToken: String
    /// Nonce brut ayant servi à obtenir ce jeton.
    let nonce: String
    let givenName: String?
}

/// Nonce d'une connexion Apple : Apple reçoit `sha256`, le serveur `raw`.
struct AppleNonce: Equatable, Sendable {
    let raw: String
    let sha256: String
}

/// Tentative de connexion Google en cours (page web du système).
struct GoogleAttempt: Equatable, Sendable {
    let url: URL
    let callbackScheme: String
    let redirectUri: String
    let clientId: String
    let state: String
    let codeVerifier: String
    let nonce: String
}

/// Seul point d'entrée de l'interface vers le compte géré par le cœur.
///
/// Les appels se font hors du fil principal ; l'implémentation réelle
/// (`LiveAccountClient`) parle à Rust, les tests utilisent une doublure.
/// Chaque erreur levée est une `AccountFailure`.
protocol AccountClient: Sendable {
    func coreVersion() async -> String
    /// Compte gardé sur l'appareil, sans réseau.
    func currentUser() async throws -> Profile?
    /// Profil à jour depuis le serveur.
    func refreshProfile() async throws -> Profile
    func newAppleNonce() async throws -> AppleNonce
    func beginGoogle() async throws -> GoogleAttempt
    func completeGoogle(_ attempt: GoogleAttempt, callback: URL) async throws -> IdentityCredential
    /// Une personne inconnue du serveur obtient un compte en attente (`.pending`).
    func signIn(_ credential: IdentityCredential) async throws -> Profile
    /// Toujours réussie : la session locale est effacée même sans réseau.
    func signOut() async
    func deleteAccount(appleAuthorizationCode: String?) async throws
}
