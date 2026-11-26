import Foundation

/// Implémentation réelle : appelle le compte UniFFI du cœur Rust.
///
/// Un acteur : les appels quittent toujours le fil principal (règle du
/// projet : jamais le cœur sur le fil principal). Le compte lui-même vient de
/// `CoreAccountSource`, partagé avec le catalogue.
actor LiveAccountClient: AccountClient {
    private let source: CoreAccountSource

    init(source: CoreAccountSource) {
        self.source = source
    }

    /// Exécute un appel au cœur et traduit ses erreurs.
    private func call<T: Sendable>(_ body: (Account) async throws -> T) async throws(AccountFailure) -> T {
        let account = try await source.account()
        do {
            return try await body(account)
        } catch {
            throw AccountFailure(error)
        }
    }

    func coreVersion() async -> String {
        HeyWatchlist.coreVersion()
    }

    func currentUser() async throws -> Profile? {
        try await call { await $0.currentUser()?.profile }
    }

    func refreshProfile() async throws -> Profile {
        try await call { try await $0.refreshProfile().profile }
    }

    func newAppleNonce() async throws -> AppleNonce {
        do {
            let nonce = try newSignInNonce()
            return AppleNonce(raw: nonce.raw, sha256: nonce.sha256)
        } catch {
            throw AccountFailure(error)
        }
    }

    func beginGoogle() async throws -> GoogleAttempt {
        let ids = try await call { try await $0.googleClientIds() }
        guard let clientId = ids.ios else { throw AccountFailure.providerUnavailable }
        do {
            let auth = try beginGoogleSignIn(clientId: clientId)
            guard let url = URL(string: auth.url) else { throw AccountFailure.unexpected }
            return GoogleAttempt(
                url: url,
                callbackScheme: auth.callbackScheme,
                redirectUri: auth.redirectUri,
                clientId: auth.clientId,
                state: auth.state,
                codeVerifier: auth.codeVerifier,
                nonce: auth.nonce
            )
        } catch {
            throw AccountFailure(error)
        }
    }

    func completeGoogle(_ attempt: GoogleAttempt, callback: URL) async throws -> IdentityCredential {
        let auth = GoogleAuthorization(
            url: attempt.url.absoluteString,
            callbackScheme: attempt.callbackScheme,
            redirectUri: attempt.redirectUri,
            clientId: attempt.clientId,
            state: attempt.state,
            codeVerifier: attempt.codeVerifier,
            nonce: attempt.nonce
        )
        do {
            let token = try await completeGoogleSignIn(authorization: auth, callbackUrl: callback.absoluteString)
            return IdentityCredential(provider: .google, idToken: token, nonce: attempt.nonce, givenName: nil)
        } catch {
            throw AccountFailure(error)
        }
    }

    func signIn(_ credential: IdentityCredential, invitationCode: String?) async throws -> SignInOutcome {
        let request = SignInRequest(
            provider: credential.provider == .apple ? .apple : .google,
            idToken: credential.idToken,
            nonce: credential.nonce,
            invitationCode: invitationCode,
            givenName: credential.givenName
        )
        let account = try await source.account()
        do {
            return .signedIn(try await account.signIn(request: request).profile)
        } catch AccountError.InvitationRequired {
            return .invitationRequired
        } catch {
            throw AccountFailure(error)
        }
    }

    func signOut() async {
        guard let account = try? await source.account() else { return }
        await account.signOut()
    }

    func deleteAccount(appleAuthorizationCode: String?) async throws {
        try await call { try await $0.deleteAccount(appleAuthorizationCode: appleAuthorizationCode) }
    }
}

private extension UserProfile {
    var profile: Profile {
        Profile(firstName: displayName, isAdmin: role == .admin, hasApple: hasApple)
    }
}
