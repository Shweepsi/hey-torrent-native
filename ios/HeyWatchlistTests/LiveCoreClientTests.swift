import Foundation
import Testing

@testable import HeyWatchlist

/// Test d'intégration : appelle le vrai cœur Rust lié à l'app (qui héberge les
/// tests). Il vérifie le pont UniFFI de bout en bout ; les tests de logique,
/// eux, n'utilisent que les doublures.
struct LiveCoreClientTests {
    @Test func theCoreAnswersPongSynchronouslyAndAsynchronously() async throws {
        let status = try await LiveCoreClient().status()

        #expect(status.pong == "pong")
        #expect(status.asyncPong == "pong")
    }

    /// La version du cœur (Cargo.toml) et celle de l'app (Version.xcconfig,
    /// écrit depuis le même Cargo.toml) doivent rester identiques.
    @Test func theCoreVersionIsTheAppVersion() async throws {
        let status = try await LiveCoreClient().status()
        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")

        #expect(status.version == appVersion as? String)
    }
}
