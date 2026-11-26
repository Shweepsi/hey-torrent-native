import Foundation
import Testing

@testable import HeyWatchlist

@MainActor
struct WatchlistModelTests {
    @Test func openingShowsTheLocalCopyThenSynchronises() async {
        let client = FakeWatchlistClient()
        await client.set(stored: [.sample(603, state: .inProgress)])
        let model = WatchlistModel(client: client)
        #expect(!model.loaded)

        await model.open()
        #expect(await client.calls == ["entries", "refresh"])
        #expect(model.loaded)
        #expect(!model.refreshing)
        #expect(model.entry(for: TitleRef(kind: .movie, id: 603))?.state == .inProgress)
        #expect(model.entry(for: TitleRef(kind: .series, id: 603)) == nil)
    }

    @Test func addingASeriesSendsTheChosenSeasons() async {
        let client = FakeWatchlistClient()
        let model = WatchlistModel(client: client)
        let series = Title.sample(95396, kind: .series)
        await model.add(series, seasons: [1, 2])
        #expect(await client.calls == ["add 95396 [1, 2]"])
        #expect(model.entry(for: series.ref)?.seasons == [1, 2])
        #expect(model.busy.isEmpty)
    }

    @Test func offlineChangesAreShownAsPending() async {
        let client = FakeWatchlistClient()
        await client.set(offline: true)
        let model = WatchlistModel(client: client)
        let movie = Title.sample(603)
        await model.add(movie, seasons: nil)
        #expect(model.offline)
        #expect(model.entry(for: movie.ref)?.pending == true)
        #expect(model.message == nil)
    }

    @Test func aRefusedChangeShowsAMessageAndRereadsTheLocalCopy() async {
        let client = FakeWatchlistClient()
        await client.fail(next: .rejected)
        let model = WatchlistModel(client: client)
        let movie = Title.sample(999)
        await model.add(movie, seasons: nil)
        #expect(await client.calls == ["add 999 entière", "entries"])
        #expect(model.message == .rejected)
        #expect(model.entry(for: movie.ref) == nil)
    }

    @Test func removingATitleTakesItOutOfTheList() async {
        let client = FakeWatchlistClient()
        await client.set(stored: [.sample(603)])
        let model = WatchlistModel(client: client)
        await model.open()
        await model.remove(TitleRef(kind: .movie, id: 603))
        #expect(model.entries.isEmpty)
    }

    @Test func aFailedRefreshKeepsWhatIsShown() async {
        let client = FakeWatchlistClient()
        await client.set(stored: [.sample(603)])
        await client.fail(next: .unexpected)
        let model = WatchlistModel(client: client)
        await model.open()
        #expect(model.entries.count == 1)
        #expect(model.message == .unexpected)
        #expect(!model.refreshing)
    }
}

struct WatchlistFailureTests {
    @Test func coreErrorsMapToTheirFailure() {
        #expect(WatchlistFailure(WatchlistError.SignedOut) == .signedOut)
        #expect(WatchlistFailure(WatchlistError.Rejected) == .rejected)
        #expect(WatchlistFailure(WatchlistError.Internal) == .unexpected)
        #expect(WatchlistFailure(WatchlistFailure.notConfigured) == .notConfigured)
    }

    @Test func everyFailureHasItsOwnFrenchMessage() {
        let messages = WatchlistFailure.allCases.map { String(localized: $0.message) }
        #expect(Set(messages).count == WatchlistFailure.allCases.count)
        #expect(messages.allSatisfy { !$0.hasPrefix("watchlist.") && !$0.hasPrefix("catalog.") })
    }
}
