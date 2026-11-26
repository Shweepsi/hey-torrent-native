import Foundation
import Testing

@testable import HeyWatchlist

struct CatalogFailureTests {
    @Test func coreErrorsMapToTheirFailure() {
        #expect(CatalogFailure(CatalogError.Network) == .network)
        #expect(CatalogFailure(CatalogError.SignedOut) == .signedOut)
        #expect(CatalogFailure(CatalogError.NotFound) == .notFound)
        #expect(CatalogFailure(CatalogError.Unavailable) == .unavailable)
        #expect(CatalogFailure(CatalogError.Server) == .server)
        #expect(CatalogFailure(CatalogError.Unexpected) == .unexpected)
        #expect(CatalogFailure(CatalogError.Internal) == .unexpected)
    }

    @Test func aFailureStaysItself() {
        #expect(CatalogFailure(CatalogFailure.notConfigured) == .notConfigured)
    }

    @Test func foreignErrorsAreUnexpected() {
        struct Unrelated: Error {}
        #expect(CatalogFailure(Unrelated()) == .unexpected)
    }

    /// Un message manquant dans le catalogue de textes afficherait la clé brute.
    @Test func everyFailureHasItsOwnFrenchMessage() {
        let messages = CatalogFailure.allCases.map { String(localized: $0.message) }
        #expect(Set(messages).count == CatalogFailure.allCases.count)
        #expect(messages.allSatisfy { !$0.hasPrefix("catalog.error.") })
    }
}
