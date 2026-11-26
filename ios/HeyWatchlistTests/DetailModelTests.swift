import Foundation
import Testing

@testable import HeyWatchlist

@MainActor
struct DetailModelTests {
    private let movie = Title.sample(603)
    private let series = Title.sample(1399, kind: .series)

    @Test func theHeaderShowsWhatTheListKnewWhileLoading() {
        let model = DetailModel(title: movie, client: FakeCatalogClient())
        #expect(model.state == .loading)
        #expect(model.shownTitle == movie)
        #expect(model.sheet == nil)
    }

    @Test func startLoadsTheSheet() async {
        let client = FakeCatalogClient()
        let model = DetailModel(title: movie, client: client)
        await model.start()
        #expect(model.sheet == TitleSheet.sample(movie.ref))
        #expect(await client.sheetCalls == [movie.ref])
    }

    @Test func comingBackToTheSheetDoesNotReloadIt() async {
        let client = FakeCatalogClient()
        let model = DetailModel(title: movie, client: client)
        await model.start()
        await model.start()
        #expect(await client.sheetCalls.count == 1)
    }

    @Test func aFailureCanBeRetried() async {
        let client = FakeCatalogClient()
        await client.set(sheet: { _ in throw CatalogFailure.notFound })
        let model = DetailModel(title: movie, client: client)
        await model.start()
        #expect(model.state == .failed(.notFound))

        await client.set(sheet: { ref in TitleSheet.sample(ref) })
        await model.retry()
        #expect(model.sheet != nil)
    }

    @Test func aSheetFromTheCacheIsFlaggedStale() async {
        let client = FakeCatalogClient()
        await client.set(sheet: { ref in TitleSheet.sample(ref, stale: true) })
        let model = DetailModel(title: movie, client: client)
        await model.start()
        #expect(model.sheet?.stale == true)
    }

    @Test func openingASeasonLoadsItsEpisodes() async {
        let client = FakeCatalogClient()
        let model = DetailModel(title: series, client: client)
        let entry = SeasonEntry.sample(2)
        await model.openSeason(entry)
        #expect(model.season?.entry == entry)
        #expect(model.season?.episodes.value?.episodes.map(\.name) == ["Pilote", "Suite"])
        #expect(await client.seasonCalls == [SeasonCall(series: 1399, number: 2)])
    }

    @Test func aSeasonFailureCanBeRetried() async {
        let client = FakeCatalogClient()
        await client.set(season: { _, _ in throw CatalogFailure.network })
        let model = DetailModel(title: series, client: client)
        await model.openSeason(.sample(1))
        #expect(model.season?.episodes == .failed(.network))

        await client.set(season: { _, number in SeasonEpisodes.sample(number) })
        await model.retrySeason()
        #expect(model.season?.episodes.value != nil)
    }

    @Test func anAnswerForAClosedSeasonIsIgnored() async {
        let client = FakeCatalogClient()
        let started = Gate()
        let release = Gate()
        await client.set(season: { _, number in
            await started.open()
            await release.wait()
            return SeasonEpisodes.sample(number)
        })
        let model = DetailModel(title: series, client: client)
        let opening = Task { await model.openSeason(.sample(1)) }
        await started.wait()

        model.season = nil
        await release.open()
        await opening.value
        #expect(model.season == nil)
    }
}
