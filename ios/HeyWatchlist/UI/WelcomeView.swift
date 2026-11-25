import AuthenticationServices
import SwiftUI

/// Accueil : ce qu'est l'app (elle se connecte au serveur personnel d'un
/// proche), connexion Apple ou Google, puis code d'invitation si besoin.
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
                            if state.askInvitation {
                                InvitationForm(model: model, busy: state.busy)
                            } else {
                                signInButtons
                            }
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

/// Saisie du code d'invitation, à la première connexion.
private struct InvitationForm: View {
    let model: AccountModel
    let busy: Bool
    @State private var code = ""

    var body: some View {
        Text("invitation.title")
            .font(.title2.bold())
        Text("invitation.body")
            .foregroundStyle(.secondary)
        TextField("invitation.placeholder", text: $code)
            .font(.title3.monospaced())
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .onSubmit(submit)
            .padding(14)
            .glassEffect(.regular, in: .rect(cornerRadius: 16))
            .accessibilityLabel(Text("invitation.label"))
        Button(action: submit) {
            Text(busy ? LocalizedStringKey("welcome.signingIn") : "invitation.submit")
                .frame(maxWidth: .infinity, minHeight: 34)
        }
        .buttonStyle(.glassProminent)
        .disabled(busy || code.trimmingCharacters(in: .whitespaces).isEmpty)
        Button {
            Task { await model.cancelInvitation() }
        } label: {
            Text("invitation.cancel")
                .frame(maxWidth: .infinity, minHeight: 34)
        }
        .buttonStyle(.glass)
        .disabled(busy)
    }

    private func submit() {
        guard !busy, !code.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let value = code
        Task { await model.submitInvitation(value) }
    }
}
