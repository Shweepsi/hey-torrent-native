import Foundation

@testable import HeyWatchlist

extension Profile {
    static let camille = Profile(firstName: "Camille", isAdmin: false, hasApple: true)
}

extension IdentityCredential {
    static let google = IdentityCredential(provider: .google, idToken: "jeton", nonce: "nonce", givenName: nil)
}

/// Doublure du compte, sans toucher au cœur natif. Les réponses se règlent
/// test par test ; les appels reçus sont notés.
actor FakeAccountClient: AccountClient {
    private let stored: Profile?
    private var refreshResult: Result<Profile, AccountFailure> = .failure(.network)
    private var signInResult: @Sendable (String?) -> Result<SignInOutcome, AccountFailure> = { _ in
        .success(.invitationRequired)
    }
    private var deleteResult: Result<Void, AccountFailure> = .success(())
    private var googleIosClient = true
    private(set) var codes: [String?] = []
    private(set) var deletedWithCode: String??
    private(set) var signedOut = false

    init(stored: Profile? = nil) {
        self.stored = stored
    }

    func set(refresh: Result<Profile, AccountFailure>) { refreshResult = refresh }
    func set(signIn: @escaping @Sendable (String?) -> Result<SignInOutcome, AccountFailure>) { signInResult = signIn }
    func set(delete: Result<Void, AccountFailure>) { deleteResult = delete }
    func set(googleIosClient: Bool) { self.googleIosClient = googleIosClient }

    func coreVersion() async -> String { "0.2.0" }
    func currentUser() async throws -> Profile? { stored }
    func refreshProfile() async throws -> Profile { try refreshResult.get() }
    func newAppleNonce() async throws -> AppleNonce { AppleNonce(raw: "brut", sha256: "condense") }

    func beginGoogle() async throws -> GoogleAttempt {
        guard googleIosClient else { throw AccountFailure.providerUnavailable }
        return GoogleAttempt(
            url: URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!,
            callbackScheme: "com.googleusercontent.apps.test",
            redirectUri: "com.googleusercontent.apps.test:/oauth2redirect",
            clientId: "test.apps.googleusercontent.com",
            state: "etat",
            codeVerifier: "verif",
            nonce: "nonce"
        )
    }

    func completeGoogle(_ attempt: GoogleAttempt, callback: URL) async throws -> IdentityCredential { .google }

    func signIn(_ credential: IdentityCredential, invitationCode: String?) async throws -> SignInOutcome {
        codes.append(invitationCode)
        return try signInResult(invitationCode).get()
    }

    func signOut() async { signedOut = true }

    func deleteAccount(appleAuthorizationCode: String?) async throws {
        deletedWithCode = .some(appleAuthorizationCode)
        try deleteResult.get()
    }
}

/// Page web Google simulée : rend tout de suite une adresse de retour.
let fakeWebAuthentication: @Sendable (URL, String) async throws -> URL = { _, scheme in
    URL(string: "\(scheme):/oauth2redirect?state=etat&code=code")!
}
