import Foundation
import Testing

@testable import HeyWatchlist

struct CoreFailureTests {
    @Test func coreErrorMapsToInternalFailure() {
        #expect(CoreFailure(CoreError.Internal) == .internalError)
    }

    @Test func anyOtherErrorMapsToUnexpectedFailure() {
        #expect(CoreFailure(UnrelatedError()) == .unexpected)
    }

    /// Un message non traduit s'afficherait sous la forme de sa clé (« core.error… »).
    @Test(arguments: [CoreFailure.internalError, .unexpected])
    func messageIsTranslated(failure: CoreFailure) {
        let text = String(localized: failure.message)

        #expect(!text.isEmpty)
        #expect(!text.hasPrefix("core.error."))
    }

    @Test func eachFailureHasItsOwnMessage() {
        #expect(
            String(localized: CoreFailure.internalError.message)
                != String(localized: CoreFailure.unexpected.message))
    }
}
