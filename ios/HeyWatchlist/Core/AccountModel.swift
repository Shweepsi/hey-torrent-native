import Foundation
import Observation

/// État de l'app vis-à-vis du compte : connexion, invitation, Réglages.
@MainActor
@Observable
final class AccountModel {
    enum State: Equatable, Sendable {
        /// Lecture de la session gardée sur l'appareil.
        case starting
        case signedOut(SignedOut)
        case signedIn(SignedIn)
    }

    struct SignedOut: Equatable, Sendable {
        var busy = false
        /// Le serveur ne connaît pas cette personne : on demande son code.
        var askInvitation = false
        var failure: AccountFailure?
    }

    struct SignedIn: Equatable, Sendable {
        var profile: Profile
        var coreVersion: String
        var busy = false
        var failure: AccountFailure?
    }

    private(set) var state: State = .starting
    /// Nonce prêt pour le prochain « Se connecter avec Apple » (demande synchrone).
    private(set) var appleNonce: AppleNonce?

    private let client: any AccountClient
    /// Jeton en attente d'un code d'invitation (le serveur l'accepte de nouveau).
    private var pendingCredential: IdentityCredential?

    init(client: any AccountClient) {
        self.client = client
    }

    func start() async {
        guard state == .starting else { return }
        do {
            guard let profile = try await client.currentUser() else {
                await prepareSignedOut(SignedOut())
                return
            }
            state = .signedIn(SignedIn(profile: profile, coreVersion: await client.coreVersion()))
            await refresh()
        } catch {
            await prepareSignedOut(SignedOut(failure: AccountFailure(error)))
        }
    }

    /// Met à jour le prénom ; sans réseau, on garde ce qu'on sait.
    func refresh() async {
        guard case .signedIn(var signedIn) = state else { return }
        do {
            signedIn.profile = try await client.refreshProfile()
            if case .signedIn = state { state = .signedIn(signedIn) }
        } catch AccountFailure.signedOut {
            await prepareSignedOut(SignedOut(failure: .signedOut))
        } catch {}
    }

    private func prepareSignedOut(_ signedOut: SignedOut) async {
        state = .signedOut(signedOut)
        appleNonce = try? await client.newAppleNonce()
    }

    // MARK: Connexion

    /// Résultat de la fenêtre « Se connecter avec Apple ».
    func completeApple(idToken: String?, givenName: String?) async {
        guard let nonce = appleNonce, let idToken else {
            await fail(.invalidIdentity)
            return
        }
        let credential = IdentityCredential(provider: .apple, idToken: idToken, nonce: nonce.raw, givenName: givenName)
        await signIn(with: credential)
    }

    /// La fenêtre Apple a échoué ou a été fermée.
    func appleFailed(cancelled: Bool) async {
        await fail(cancelled ? .cancelled : .providerUnavailable)
    }

    /// Connexion Google : `authenticate` ouvre la page web du système et rend
    /// l'adresse de retour.
    func signInWithGoogle(authenticate: (URL, String) async throws -> URL) async {
        guard begin() else { return }
        do {
            let attempt = try await client.beginGoogle()
            let callback = try await authenticate(attempt.url, attempt.callbackScheme)
            let credential = try await client.completeGoogle(attempt, callback: callback)
            try await submit(credential, code: nil)
        } catch {
            await fail(AccountFailure(error))
        }
    }

    func submitInvitation(_ code: String) async {
        guard let credential = pendingCredential, begin() else { return }
        do {
            try await submit(credential, code: code.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            await fail(AccountFailure(error))
        }
    }

    func cancelInvitation() async {
        pendingCredential = nil
        await prepareSignedOut(SignedOut())
    }

    private func signIn(with credential: IdentityCredential) async {
        guard begin() else { return }
        do {
            try await submit(credential, code: nil)
        } catch {
            await fail(AccountFailure(error))
        }
    }

    private func submit(_ credential: IdentityCredential, code: String?) async throws {
        pendingCredential = credential
        switch try await client.signIn(credential, invitationCode: code) {
        case .invitationRequired:
            state = .signedOut(SignedOut(askInvitation: true))
        case .signedIn(let profile):
            pendingCredential = nil
            state = .signedIn(SignedIn(profile: profile, coreVersion: await client.coreVersion()))
        }
    }

    /// Passe l'écran de connexion en « occupé » ; faux s'il l'était déjà.
    private func begin() -> Bool {
        guard case .signedOut(var signedOut) = state, !signedOut.busy else { return false }
        signedOut.busy = true
        signedOut.failure = nil
        state = .signedOut(signedOut)
        return true
    }

    private func fail(_ failure: AccountFailure) async {
        guard case .signedOut(var signedOut) = state else { return }
        signedOut.busy = false
        // Fenêtre fermée par la personne : pas de message.
        signedOut.failure = failure == .cancelled ? nil : failure
        state = .signedOut(signedOut)
        // Un nonce ne sert qu'une fois : on en prépare un autre.
        appleNonce = try? await client.newAppleNonce()
    }

    // MARK: Réglages

    func signOut() async {
        guard case .signedIn(var signedIn) = state, !signedIn.busy else { return }
        signedIn.busy = true
        state = .signedIn(signedIn)
        await client.signOut()
        await prepareSignedOut(SignedOut())
    }

    /// Supprime le compte ; `appleAuthorizationCode` vient d'une nouvelle
    /// autorisation Apple si le compte y est rattaché.
    func deleteAccount(appleAuthorizationCode: String?) async {
        guard case .signedIn(var signedIn) = state, !signedIn.busy else { return }
        signedIn.busy = true
        signedIn.failure = nil
        state = .signedIn(signedIn)
        do {
            try await client.deleteAccount(appleAuthorizationCode: appleAuthorizationCode)
            await prepareSignedOut(SignedOut())
        } catch AccountFailure.signedOut {
            await prepareSignedOut(SignedOut(failure: .signedOut))
        } catch {
            signedIn.busy = false
            signedIn.failure = AccountFailure(error)
            state = .signedIn(signedIn)
        }
    }

    /// L'autorisation Apple demandée avant la suppression a échoué.
    func deletionAuthorizationFailed(cancelled: Bool) {
        guard case .signedIn(var signedIn) = state, !cancelled else { return }
        signedIn.failure = .providerUnavailable
        state = .signedIn(signedIn)
    }
}
