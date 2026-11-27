import SwiftUI

/// Compte connecté mais pas encore accepté (ou refusé) : rien d'autre de l'app
/// n'est accessible. L'accès est relu à chaque retour dans l'app et quand une
/// notification annonce l'acceptation.
struct WaitingView: View {
    let model: AccountModel
    let state: AccountModel.SignedIn
    @State private var confirmingDeletion = false
    @Environment(\.scenePhase) private var scenePhase

    private var refused: Bool { state.profile.access == .refused }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                GlassEffectContainer(spacing: 12) {
                    VStack(alignment: .leading, spacing: 24) {
                        Text("app.name")
                            .font(.largeTitle.bold())
                            .accessibilityAddTraits(.isHeader)
                        VStack(alignment: .leading, spacing: 16) {
                            title
                                .font(.title2.bold())
                            Text(refused ? LocalizedStringKey("waiting.refusedBody") : "waiting.body")
                                .foregroundStyle(.secondary)
                            if !refused {
                                Button {
                                    Task { await model.checkAccess() }
                                } label: {
                                    Text(state.checking ? LocalizedStringKey("waiting.checking") : "waiting.check")
                                        .frame(maxWidth: .infinity, minHeight: 34)
                                }
                                .buttonStyle(.glassProminent)
                                .disabled(state.checking || state.busy)
                            }
                            if let failure = state.failure {
                                Text(failure.message)
                                    .foregroundStyle(.red)
                            }
                        }
                        .padding(24)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .glassEffect(.regular, in: .rect(cornerRadius: 28))

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
                            Text(refused ? LocalizedStringKey("settings.delete") : "waiting.withdraw")
                                .frame(maxWidth: .infinity, minHeight: 34)
                        }
                        .buttonStyle(.glass)
                        .disabled(state.busy)
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .sheet(isPresented: $confirmingDeletion) {
            DeleteAccountSheet(model: model, state: state, isPresented: $confirmingDeletion)
                .presentationDetents([.medium])
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.checkAccess() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: PushCenter.accessGranted)) { _ in
            Task { await model.checkAccess() }
        }
    }

    @ViewBuilder
    private var title: some View {
        if refused {
            Text("waiting.refusedTitle")
        } else if let name = state.profile.firstName {
            Text("waiting.titleNamed \(name)")
        } else {
            Text("waiting.title")
        }
    }
}
