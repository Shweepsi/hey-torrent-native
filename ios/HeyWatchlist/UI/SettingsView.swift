import AuthenticationServices
import SwiftUI

/// Réglages : prénom du compte, déconnexion, suppression du compte, version.
struct SettingsView: View {
    let model: AccountModel
    let state: AccountModel.SignedIn
    @State private var confirmingDeletion = false

    var body: some View {
        NavigationStack {
            ZStack {
                HeyBackground()
                ScrollView {
                    GlassEffectContainer(spacing: 12) {
                        VStack(alignment: .leading, spacing: 16) {
                            accountCard
                            NotificationsCard()
                            if let failure = state.failure {
                                Text(failure.message)
                                    .foregroundStyle(.red)
                            }
                            Button {
                                Task { await model.signOut() }
                            } label: {
                                Text("settings.signOut").frame(maxWidth: .infinity, minHeight: 34)
                            }
                            .buttonStyle(.glass)
                            .disabled(state.busy)
                            Button(role: .destructive) {
                                confirmingDeletion = true
                            } label: {
                                Text("settings.delete").frame(maxWidth: .infinity, minHeight: 34)
                            }
                            .buttonStyle(.glass)
                            .disabled(state.busy)
                            if !state.coreVersion.isEmpty {
                                Text("settings.version \(state.coreVersion)")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(24)
                }
            }
            .navigationTitle("tab.settings")
            .refreshable { await model.refresh() }
            .sheet(isPresented: $confirmingDeletion) {
                DeleteAccountSheet(model: model, state: state, isPresented: $confirmingDeletion)
                    .presentationDetents([.medium])
            }
        }
    }

    private var accountCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let name = state.profile.firstName {
                Text("settings.hello \(name)")
                    .font(.title2.bold())
            } else {
                Text("settings.helloAnonymous")
                    .font(.title2.bold())
            }
            Text(state.profile.isAdmin ? LocalizedStringKey("settings.roleAdmin") : "settings.roleMember")
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
    }
}

/// Confirmation de la suppression. Pour un compte Apple, la confirmation passe
/// par une nouvelle autorisation Apple, qui permet au serveur de la révoquer.
private struct DeleteAccountSheet: View {
    let model: AccountModel
    let state: AccountModel.SignedIn
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("delete.title")
                .font(.title2.bold())
            Text("delete.body")
                .foregroundStyle(.secondary)
            if state.profile.hasApple {
                Text("delete.appleHint")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SignInWithAppleButton(.continue) { request in
                    request.requestedScopes = []
                } onCompletion: { result in
                    switch result {
                    case .success(let authorization):
                        let credential = authorization.credential as? ASAuthorizationAppleIDCredential
                        let code = credential?.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
                        isPresented = false
                        Task { await model.deleteAccount(appleAuthorizationCode: code) }
                    case .failure(let error):
                        isPresented = false
                        model.deletionAuthorizationFailed(cancelled: (error as? ASAuthorizationError)?.code == .canceled)
                    }
                }
                .signInWithAppleButtonStyle(.white)
                .frame(height: 50)
            } else {
                Button(role: .destructive) {
                    isPresented = false
                    Task { await model.deleteAccount(appleAuthorizationCode: nil) }
                } label: {
                    Text("delete.confirm").frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.glassProminent)
                .tint(.red)
            }
            Button {
                isPresented = false
            } label: {
                Text("delete.cancel").frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.glass)
        }
        .padding(24)
        .disabled(state.busy)
    }
}
