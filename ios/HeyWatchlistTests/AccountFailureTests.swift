import Testing

@testable import HeyWatchlist

struct AccountFailureTests {
    @Test func coreErrorsMapToTheirFailure() {
        #expect(AccountFailure(AccountError.Network) == .network)
        #expect(AccountFailure(AccountError.InvitationInvalid) == .invitationInvalid)
        #expect(AccountFailure(AccountError.SignedOut) == .signedOut)
        #expect(AccountFailure(AccountError.InvalidConfiguration) == .notConfigured)
        #expect(AccountFailure(AccountError.Internal) == .unexpected)
    }

    @Test func aFailureStaysItself() {
        #expect(AccountFailure(AccountFailure.cancelled) == .cancelled)
    }

    @Test func foreignErrorsAreUnexpected() {
        struct Unrelated: Error {}
        #expect(AccountFailure(Unrelated()) == .unexpected)
    }
}
