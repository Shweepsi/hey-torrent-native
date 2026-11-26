import Foundation
import Testing

@testable import HeyWatchlist

struct FormattingTests {
    @Test func aRatingHasOneFrenchDecimal() {
        #expect(Formatting.rating(7.44) == "7,4")
        #expect(Formatting.rating(8) == "8,0")
    }

    @Test func aShortRuntimeIsInMinutes() {
        #expect(Formatting.runtime(minutes: 45) == "45 min")
    }

    @Test func aLongRuntimeIsInHoursAndMinutes() {
        #expect(Formatting.runtime(minutes: 112) == "1 h 52")
        #expect(Formatting.runtime(minutes: 125) == "2 h 05")
    }

    @Test func aDateIsWrittenInFrench() {
        #expect(Formatting.date("2025-03-12") == "12 mars 2025")
    }

    @Test func anUnreadableDateIsDropped() {
        #expect(Formatting.date("2025-03") == nil)
        #expect(Formatting.date("bientôt") == nil)
    }
}
