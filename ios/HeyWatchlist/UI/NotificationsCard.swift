import SwiftUI

/// Notifications dans Réglages : état de la permission, et le chemin vers les
/// réglages du téléphone quand elles sont coupées.
struct NotificationsCard: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var allowed: Bool?

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
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
        .task(id: scenePhase) { allowed = await PushCenter.shared.isAllowed() }
    }
}
