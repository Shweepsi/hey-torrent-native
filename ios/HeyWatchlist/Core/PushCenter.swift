import UIKit
import UserNotifications

/// Notifications « Disponible sur Plex » sur iPhone, par APNs (sans Firebase).
///
/// Le système donne un jeton d'appareil ; le cœur l'enregistre auprès du
/// serveur pour la session en cours. Le texte des notifications n'est pas
/// envoyé par le serveur : il envoie une clé (`loc-key`) que le système
/// traduit avec `Localizable.xcstrings`.
@MainActor
final class PushCenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = PushCenter()
    /// Publiée quand une notification annonce que l'accès du compte est accepté.
    static let accessGranted = Notification.Name("HeyWatchlistAccessGranted")

    private var source: CoreAccountSource?

    /// Au lancement : le compte partagé, et l'affichage des notifications
    /// reçues pendant que l'app est ouverte.
    func configure(source: CoreAccountSource) {
        self.source = source
        UNUserNotificationCenter.current().delegate = self
    }

    /// Une fois connecté : demande la permission (une seule fois, le système
    /// retient la réponse), puis le jeton. Le jeton est enregistré même sans
    /// permission : l'autoriser plus tard dans Réglages suffit alors.
    func start() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// Jeton reçu d'APNs (via `AppDelegate`), à chaque lancement.
    func didRegister(deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        Task { await register(token) }
    }

    private func register(_ token: String) async {
        guard let source else { return }
        do {
            let account = try await source.account()
            try await account.registerPushDevice(platform: .ios, token: token)
        } catch {
            // Sans réseau : réessayé au prochain lancement.
        }
    }

    /// Permission accordée pour afficher des alertes.
    func isAllowed() async -> Bool {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        return settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    }

    /// Notification d'essai vers les appareils de ce compte.
    func sendTest() async throws(AccountFailure) -> TestPushResult {
        guard let source else { throw .notConfigured }
        let account = try await source.account()
        do {
            return try await account.sendTestPush()
        } catch {
            throw AccountFailure(error)
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let aps = notification.request.content.userInfo["aps"] as? [String: Any]
        let alert = aps?["alert"] as? [String: Any]
        if alert?["loc-key"] as? String == "push_access_granted" {
            await MainActor.run { NotificationCenter.default.post(name: PushCenter.accessGranted, object: nil) }
        }
        return [.banner, .list, .sound]
    }
}

/// Reçoit le jeton APNs, que SwiftUI ne donne pas autrement.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushCenter.shared.didRegister(deviceToken: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: any Error) {
        // Simulateur, ou capacité absente du profil : pas de notification.
    }
}
