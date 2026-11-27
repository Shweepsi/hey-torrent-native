import AuthenticationServices
import SwiftUI

/// Accueil : ce qu'est l'app (elle se connecte au serveur personnel d'un
/// proche) et connexion Apple ou Google. Un nouveau compte attend ensuite
/// d'être accepté (`WaitingView`).
struct WelcomeView: View {
    let model: AccountModel
    let state: AccountModel.SignedOut

    @Environment(\.webAuthenticationSession) private var webAuthenticationSession

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                GlassEffectContainer(spacing: 12) {
                    VStack(alignment: .leading, spacing: 24) {
                        Text("app.name")
                            .font(.largeTitle.bold())
                            .accessibilityAddTraits(.isHeader)
                        VStack(alignment: .leading, spacing: 16) {
                            signInButtons
                            if let failure = state.failure {
                                Text(failure.message)
                                    .foregroundStyle(.red)
                            }
                        }
                        .padding(24)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .glassEffect(.regular, in: .rect(cornerRadius: 28))
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    @ViewBuilder
    private var signInButtons: some View {
        Text("welcome.title")
            .font(.title2.bold())
        Text("welcome.body")
            .foregroundStyle(.secondary)
        SignInWithAppleButton(.continue) { request in
            request.requestedScopes = [.fullName, .email]
            request.nonce = model.appleNonce?.sha256
        } onCompletion: { result in
            Task { await handleApple(result) }
        }
        .signInWithAppleButtonStyle(.white)
        .frame(height: 50)
        .disabled(state.busy || model.appleNonce == nil)

        Button {
            Task { await signInWithGoogle() }
        } label: {
            Text(state.busy ? LocalizedStringKey("welcome.signingIn") : "welcome.google")
                .frame(maxWidth: .infinity, minHeight: 34)
        }
        .buttonStyle(.glass)
        .disabled(state.busy)
    }

    private func handleApple(_ result: Result<ASAuthorization, any Error>) async {
        switch result {
        case .success(let authorization):
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential
            let token = credential?.identityToken.flatMap { String(data: $0, encoding: .utf8) }
            await model.completeApple(idToken: token, givenName: credential?.fullName?.givenName)
        case .failure(let error):
            await model.appleFailed(cancelled: (error as? ASAuthorizationError)?.code == .canceled)
        }
    }

    private func signInWithGoogle() async {
        let session = webAuthenticationSession
        await model.signInWithGoogle { url, scheme in
            do {
                // Session éphémère : rien n'est gardé dans Safari.
                return try await session.authenticate(
                    using: url,
                    callback: .customScheme(scheme),
                    preferredBrowserSession: .ephemeral,
                    additionalHeaderFields: [:]
                )
            } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
                throw AccountFailure.cancelled
            } catch {
                throw AccountFailure.providerUnavailable
            }
        }
    }
}
