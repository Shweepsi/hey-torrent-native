import Foundation
import Testing

@testable import HeyWatchlist

/// Coffre en mémoire, pour ne pas toucher au Keychain du simulateur.
final class MemoryStore: SessionStore, @unchecked Sendable {
    private var value: String?
    func load() -> String? { value }
    func save(value: String) { self.value = value }
    func clear() { value = nil }
}

/// Test d'intégration : appelle le vrai cœur Rust lié à l'app (qui héberge les
/// tests), sans réseau. Il vérifie le pont UniFFI de bout en bout.
struct LiveAccountClientTests {
    /// La version du cœur (Cargo.toml) et celle de l'app (Version.xcconfig,
    /// écrit depuis le même Cargo.toml) doivent rester identiques.
    @Test func theCoreVersionIsTheAppVersion() async {
        let version = await LiveAccountClient(baseUrl: "", store: MemoryStore()).coreVersion()
        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
        #expect(version == appVersion as? String)
    }

    @Test func withoutAServerAddressTheAppSaysSo() async {
        let client = LiveAccountClient(baseUrl: "", store: MemoryStore())
        await #expect(throws: AccountFailure.notConfigured) { try await client.currentUser() }
    }

    @Test func anInsecureAddressIsRefused() async {
        let client = LiveAccountClient(baseUrl: "http://example.com", store: MemoryStore())
        await #expect(throws: AccountFailure.notConfigured) { try await client.currentUser() }
    }

    @Test func noStoredSessionMeansSignedOut() async throws {
        let client = LiveAccountClient(baseUrl: "https://api.example.com", store: MemoryStore())
        #expect(try await client.currentUser() == nil)
    }

    @Test func appleNoncesAreHashedForApple() async throws {
        let nonce = try await LiveAccountClient(baseUrl: "", store: MemoryStore()).newAppleNonce()
        #expect(nonce.raw != nonce.sha256)
        #expect(nonce.sha256.count == 64)
    }
}
