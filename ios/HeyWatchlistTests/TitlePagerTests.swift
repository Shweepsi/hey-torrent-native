import Foundation
import Testing

@testable import HeyWatchlist

@MainActor
struct TitlePagerTests {
    @Test func theFirstPageLoads() async {
        let pager = TitlePager()
        #expect(pager.phase == .idle)
        await pager.begin { _ in .sample([1, 2], totalPages: 3) }
        #expect(pager.phase == .loaded)
        #expect(pager.items.map(\.ref.id) == [1, 2])
        #expect(pager.hasMore)
    }

    @Test func aFailedFirstPageCanBeRetried() async {
        let pager = TitlePager()
        await pager.begin { _ in throw CatalogFailure.network }
        #expect(pager.phase == .failed(.network))
        await pager.begin { _ in .sample([1]) }
        #expect(pager.phase == .loaded)
    }

    @Test func retryRunsTheSameRequestAgain() async {
        let pager = TitlePager()
        let attempts = Counter()
        await pager.begin { _ in
            if await attempts.next() == 1 { throw CatalogFailure.server }
            return .sample([7])
        }
        #expect(pager.phase == .failed(.server))
        await pager.retry()
        #expect(pager.phase == .loaded)
        #expect(pager.items.map(\.ref.id) == [7])
    }

    @Test func nextPagesLoadOnlyWhenTheEndComesIntoView() async {
        let pager = TitlePager()
        await pager.begin { page in
            page == 1
                ? TitleList.sample(Array(1...8), totalPages: 2)
                : TitleList.sample([9, 10], totalPages: 2)
        }
        pager.loadMoreIfNeeded(after: pager.items[0])
        #expect(!pager.isLoadingMore)

        pager.loadMoreIfNeeded(after: pager.items[7])
        #expect(pager.isLoadingMore)
        await pager.nextPage?.value
        #expect(pager.items.map(\.ref.id) == Array(1...10))
        #expect(!pager.isLoadingMore)
        #expect(!pager.hasMore)
    }

    @Test func pagingResumesAfterThePageTheCoreReallyReturned() async {
        let pager = TitlePager()
        let asked = Recorder()
        await pager.begin { page in
            await asked.add(page)
            if page == 1 { return TitleList.sample([1, 2], totalPages: 9) }
            // Le cœur a sauté les pages 2 et 3, vidées par le tri par langue.
            var list = TitleList.sample([3], totalPages: 9)
            if page == 2 { list.page = 4 }
            return list
        }
        pager.loadMoreIfNeeded(after: pager.items[1])
        await pager.nextPage?.value
        pager.loadMoreIfNeeded(after: pager.items[2])
        await pager.nextPage?.value
        #expect(await asked.pages == [1, 2, 5])
    }

    @Test func aTitleRepeatedOnTheNextPageAppearsOnce() async {
        let pager = TitlePager()
        await pager.begin { page in
            page == 1
                ? TitleList.sample([1, 2], totalPages: 2)
                : TitleList.sample([2, 3], totalPages: 2)
        }
        pager.loadMoreIfNeeded(after: pager.items[1])
        await pager.nextPage?.value
        #expect(pager.items.map(\.ref.id) == [1, 2, 3])
    }

    @Test func aMovieAndASeriesWithTheSameNumberAreTwoTitles() async {
        let pager = TitlePager()
        await pager.begin { _ in
            TitleList(items: [.sample(5, kind: .movie), .sample(5, kind: .series)], totalPages: 1, stale: false)
        }
        #expect(pager.items.count == 2)
    }

    @Test func aFailedNextPageKeepsTheTitlesAndCanBeRetried() async {
        let pager = TitlePager()
        let secondPage = Counter()
        await pager.begin { page in
            if page == 1 { return TitleList.sample([1, 2], totalPages: 2) }
            if await secondPage.next() == 1 { throw CatalogFailure.network }
            return TitleList.sample([3], totalPages: 2)
        }
        pager.loadMoreIfNeeded(after: pager.items[1])
        await pager.nextPage?.value
        #expect(pager.moreFailure == .network)
        #expect(pager.items.map(\.ref.id) == [1, 2])
        // Tant que l'échec est là, le défilement ne relance rien.
        pager.loadMoreIfNeeded(after: pager.items[1])
        #expect(!pager.isLoadingMore)

        pager.retryMore()
        await pager.nextPage?.value
        #expect(pager.moreFailure == nil)
        #expect(pager.items.map(\.ref.id) == [1, 2, 3])
    }

    @Test func aStaleFirstPageIsReported() async {
        let pager = TitlePager()
        await pager.begin { _ in .sample([1], stale: true) }
        #expect(pager.isStale)
    }

    @Test func anObsoleteFirstPageIsIgnored() async {
        let pager = TitlePager()
        let started = Gate()
        let release = Gate()
        let slow = Task {
            await pager.begin { _ in
                await started.open()
                await release.wait()
                return .sample([1])
            }
        }
        await started.wait()
        await pager.begin { _ in .sample([2]) }
        await release.open()
        await slow.value
        #expect(pager.items.map(\.ref.id) == [2])
        #expect(pager.phase == .loaded)
    }

    @Test func keepingTheItemsLeavesTheListUntilTheNewFirstPageArrives() async {
        let pager = TitlePager()
        await pager.begin { _ in .sample([1, 2], totalPages: 3, stale: true) }

        pager.reset(loading: true, keepingItems: true)
        #expect(pager.phase == .loading)
        #expect(pager.items.map(\.ref.id) == [1, 2])
        // Pendant ce temps, le défilement ne demande aucune page.
        pager.loadMoreIfNeeded(after: pager.items[1])
        #expect(!pager.isLoadingMore)

        await pager.begin(keepingItems: true) { _ in .sample([3]) }
        #expect(pager.phase == .loaded)
        #expect(pager.items.map(\.ref.id) == [3])
        #expect(pager.page == 1)
        #expect(!pager.hasMore)
        #expect(!pager.isStale)
    }

    @Test func aFailedFirstPageDropsTheItemsThatWereKept() async {
        let pager = TitlePager()
        await pager.begin { _ in .sample([1, 2]) }
        await pager.begin(keepingItems: true) { _ in throw CatalogFailure.network }
        #expect(pager.phase == .failed(.network))
        #expect(pager.items.isEmpty)
    }

    @Test func aNextPageOfThePreviousRequestIsCancelledWhenItemsAreKept() async {
        let pager = TitlePager()
        let started = Gate()
        let release = Gate()
        await pager.begin { page in
            if page == 1 { return TitleList.sample([1, 2], totalPages: 2) }
            await started.open()
            await release.wait()
            return TitleList.sample([3], totalPages: 2)
        }
        pager.loadMoreIfNeeded(after: pager.items[1])
        await started.wait()
        #expect(pager.isLoadingMore)
        let oldRequest = pager.nextPage

        pager.reset(loading: true, keepingItems: true)
        #expect(!pager.isLoadingMore)
        await release.open()
        await oldRequest?.value
        #expect(pager.items.map(\.ref.id) == [1, 2])
        #expect(!pager.isLoadingMore)
    }

    @Test func resetEmptiesTheList() async {
        let pager = TitlePager()
        await pager.begin { _ in .sample([1, 2]) }
        pager.reset()
        #expect(pager.items.isEmpty)
        #expect(pager.phase == .idle)
    }
}

/// Compteur d'appels, pour qu'une doublure échoue la première fois seulement.
actor Counter {
    private var value = 0

    /// Numéro de cet appel, à partir de 1.
    func next() -> Int {
        value += 1
        return value
    }
}

/// Pages demandées, dans l'ordre.
private actor Recorder {
    private(set) var pages: [Int] = []
    func add(_ page: Int) { pages.append(page) }
}
