import Foundation
import Testing

@testable import HeyWatchlist

@MainActor
struct DiscoverModelTests {
    @Test func everyRowAndTheGenresStartAsSkeletons() {
        let model = DiscoverModel(client: FakeCatalogClient())
        for shelf in DiscoverShelf.allCases {
            #expect(model.state(of: shelf) == .loading)
        }
        #expect(model.genres == .loading)
        #expect(model.heroTitles.isEmpty)
    }

    @Test func startLoadsEveryShelfAndTheMovieGenres() async {
        let client = FakeCatalogClient()
        let model = DiscoverModel(client: client)
        await model.start()
        for shelf in DiscoverShelf.allCases {
            #expect(model.state(of: shelf).value != nil)
        }
        #expect(model.genres.value?.map(\.name) == ["Action", "Comédie"])
        #expect(await client.shelfCalls.count == DiscoverShelf.allCases.count)
        #expect(await client.genreCalls == [.movie])
    }

    @Test func comingBackToTheTabDoesNotReload() async {
        let client = FakeCatalogClient()
        let model = DiscoverModel(client: client)
        await model.start()
        await model.start()
        #expect(await client.shelfCalls.count == DiscoverShelf.allCases.count)
    }

    @Test func aFailingRowDoesNotBlockTheOthers() async {
        let client = FakeCatalogClient()
        await client.set(shelf: { shelf, _ in
            if shelf == .upcoming { throw CatalogFailure.unavailable }
            return TitleList.sample([1])
        })
        let model = DiscoverModel(client: client)
        await model.start()
        #expect(model.state(of: .upcoming) == .failed(.unavailable))
        #expect(model.state(of: .trending).value?.ids == [1])
        #expect(model.state(of: .popularSeries).value?.ids == [1])
        #expect(model.genres.value != nil)
    }

    @Test func retryReloadsOnlyTheFailedRow() async {
        let client = FakeCatalogClient()
        await client.set(shelf: { _, _ in throw CatalogFailure.network })
        let model = DiscoverModel(client: client)
        await model.start()
        #expect(model.state(of: .nowPlaying) == .failed(.network))

        await client.set(shelf: { _, _ in TitleList.sample([4]) })
        await model.retry(.nowPlaying)
        #expect(model.state(of: .nowPlaying).value?.ids == [4])
        #expect(model.state(of: .upcoming) == .failed(.network))
    }

    @Test func aFailedGenresRowCanBeRetried() async {
        let client = FakeCatalogClient()
        await client.set(genres: .failure(.server))
        let model = DiscoverModel(client: client)
        await model.start()
        #expect(model.genres == .failed(.server))

        await client.set(genres: .success([TitleGenre(id: 18, name: "Drame")]))
        await model.retryGenres()
        #expect(model.genres.value == [TitleGenre(id: 18, name: "Drame")])
    }

    @Test func pullToRefreshKeepsWhatIsShownWhenTheNetworkFails() async {
        let client = FakeCatalogClient()
        let model = DiscoverModel(client: client)
        await model.start()
        let before = model.state(of: .trending)

        await client.set(shelf: { _, _ in throw CatalogFailure.network })
        await client.set(genres: .failure(.network))
        await model.refresh()
        #expect(model.state(of: .trending) == before)
        #expect(model.genres.value != nil)
    }

    @Test func pullToRefreshReplacesTheContent() async {
        let client = FakeCatalogClient()
        let model = DiscoverModel(client: client)
        await model.start()

        await client.set(shelf: { _, _ in TitleList.sample([99]) })
        await model.refresh()
        #expect(model.state(of: .trending).value?.ids == [99])
    }

    @Test func theCarouselKeepsTheFirstSixTrendingTitlesWithABackdrop() async {
        let client = FakeCatalogClient()
        await client.set(shelf: { _, _ in
            TitleList(
                items: (1...10).map { Title.sample($0, backdrop: $0 != 2) },
                totalPages: 1, stale: false
            )
        })
        let model = DiscoverModel(client: client)
        await model.start()
        #expect(model.heroTitles.map(\.ref.id) == [1, 3, 4, 5, 6, 7])
    }

    @Test func theBannerAppearsWhenARowComesFromTheCache() async {
        let client = FakeCatalogClient()
        let model = DiscoverModel(client: client)
        await model.start()
        #expect(!model.isStale)

        await client.set(shelf: { shelf, _ in TitleList.sample([1], stale: shelf == .upcoming) })
        await model.refresh()
        #expect(model.isStale)
    }
}
