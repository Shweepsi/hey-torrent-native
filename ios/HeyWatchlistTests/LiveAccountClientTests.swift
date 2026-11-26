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
    private func makeClient(baseUrl: String) -> LiveAccountClient {
        LiveAccountClient(source: CoreAccountSource(baseUrl: baseUrl, store: MemoryStore()))
    }

    /// La version du cœur (Cargo.toml) et celle de l'app (Version.xcconfig,
    /// écrit depuis le même Cargo.toml) doivent rester identiques.
    @Test func theCoreVersionIsTheAppVersion() async {
        let version = await makeClient(baseUrl: "").coreVersion()
        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
        #expect(version == appVersion as? String)
    }

    @Test func withoutAServerAddressTheAppSaysSo() async {
        let client = makeClient(baseUrl: "")
        await #expect(throws: AccountFailure.notConfigured) { try await client.currentUser() }
    }

    @Test func anInsecureAddressIsRefused() async {
        let client = makeClient(baseUrl: "http://example.com")
        await #expect(throws: AccountFailure.notConfigured) { try await client.currentUser() }
    }

    @Test func noStoredSessionMeansSignedOut() async throws {
        let client = makeClient(baseUrl: "https://api.example.com")
        #expect(try await client.currentUser() == nil)
    }

    @Test func appleNoncesAreHashedForApple() async throws {
        let nonce = try await makeClient(baseUrl: "").newAppleNonce()
        #expect(nonce.raw != nonce.sha256)
        #expect(nonce.sha256.count == 64)
    }
}
