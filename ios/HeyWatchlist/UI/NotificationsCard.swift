import SwiftUI

/// Notifications dans Réglages : état de la permission et notification
/// d'essai, pour vérifier toute la chaîne sans attendre un ajout sur Plex.
struct NotificationsCard: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var allowed: Bool?
    @State private var testing = false
    @State private var result: LocalizedStringResource?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("settings.push.title")
                .font(.title2.bold())
            Text(allowed == false ? LocalizedStringKey("settings.push.denied") : "settings.push.body")
                .foregroundStyle(.secondary)
            if allowed == false {
                Button {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
                } label: {
                    Text("settings.push.openSettings").frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.glass)
            } else if allowed == true {
                if let result {
                    Text(result).foregroundStyle(.secondary)
                }
                Button {
                    Task { await sendTest() }
                } label: {
                    Text(testing ? "settings.push.testing" : "settings.push.test")
                        .frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.glass)
                .disabled(testing)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
        .task(id: scenePhase) { allowed = await PushCenter.shared.isAllowed() }
    }

    private func sendTest() async {
        testing = true
        defer { testing = false }
        do {
            let sent = try await PushCenter.shared.sendTest()
            let message: LocalizedStringResource = if sent.devices == 0 {
                "settings.push.testNoDevice"
            } else if sent.sent == 0 {
                "settings.push.testFailed"
            } else {
                "settings.push.testSent"
            }
            result = message
        } catch {
            result = error.message
        }
    }
}
