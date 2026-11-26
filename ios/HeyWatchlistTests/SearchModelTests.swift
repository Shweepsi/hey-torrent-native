import Foundation
import Testing

@testable import HeyWatchlist

@MainActor
struct SearchModelTests {
    private func makeModel(_ client: FakeCatalogClient) -> SearchModel {
        SearchModel(client: client, debounce: .zero)
    }

    @Test func anEmptyQueryNeverCallsTheCatalog() async {
        let client = FakeCatalogClient()
        let model = makeModel(client)
        model.query = "   "
        await model.pending?.value
        #expect(model.pager.phase == .idle)
        #expect(await client.searchCalls.isEmpty)
    }

    @Test func theSkeletonShowsAsSoonAsAQueryIsTyped() {
        let model = makeModel(FakeCatalogClient())
        model.query = "dune"
        #expect(model.pager.phase == .loading)
    }

    @Test func theTrimmedQueryIsSearched() async {
        let client = FakeCatalogClient()
        let model = makeModel(client)
        model.query = "  dune "
        await model.pending?.value
        #expect(await client.searchCalls == [SearchCall(query: "dune", page: 1)])
        #expect(model.pager.phase == .loaded)
        #expect(model.pager.items.map(\.ref.id) == [1, 2, 3])
    }

    @Test func typingFastOnlySearchesTheLastQuery() async {
        let client = FakeCatalogClient()
        let model = makeModel(client)
        model.query = "d"
        model.query = "du"
        model.query = "dune"
        await model.pending?.value
        #expect(await client.searchCalls == [SearchCall(query: "dune", page: 1)])
    }

    @Test func aSpaceAroundTheQueryDoesNotRestartTheSearch() async {
        let client = FakeCatalogClient()
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value
        model.query = "dune "
        await model.pending?.value
        #expect(await client.searchCalls.count == 1)
        #expect(model.pager.phase == .loaded)
    }

    @Test func aResponseArrivingAfterANewKeystrokeIsIgnored() async {
        let client = FakeCatalogClient()
        let started = Gate()
        let release = Gate()
        await client.set(search: { query, _ in
            if query == "lent" {
                await started.open()
                await release.wait()
                return TitleList.sample([1])
            }
            return TitleList.sample([2])
        })
        let model = makeModel(client)
        model.query = "lent"
        let slow = model.pending
        await started.wait()

        model.query = "rapide"
        await model.pending?.value
        await release.open()
        await slow?.value

        #expect(model.pager.items.map(\.ref.id) == [2])
        #expect(model.pager.phase == .loaded)
    }

    @Test func theResultsOfThePreviousTermStayUntilTheNewFirstPageArrives() async {
        let client = FakeCatalogClient()
        await client.set(search: { query, _ in
            query == "dune" ? TitleList.sample([1, 2]) : TitleList.sample([5])
        })
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value

        model.query = "arrakis"
        // Dès la frappe : l'ancienne liste reste, en chargement (l'interface l'estompe).
        #expect(model.pager.phase == .loading)
        #expect(model.pager.items.map(\.ref.id) == [1, 2])

        await model.pending?.value
        #expect(model.pager.phase == .loaded)
        #expect(model.pager.items.map(\.ref.id) == [5])
    }

    @Test func thePreviousResultsStayWhileTheNewRequestIsStillRunning() async {
        let client = FakeCatalogClient()
        let started = Gate()
        let release = Gate()
        await client.set(search: { query, _ in
            if query == "arrakis" {
                await started.open()
                await release.wait()
                return TitleList.sample([5])
            }
            return TitleList.sample([1, 2])
        })
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value

        model.query = "arrakis"
        let slow = model.pending
        await started.wait()
        #expect(model.pager.phase == .loading)
        #expect(model.pager.items.map(\.ref.id) == [1, 2])

        await release.open()
        await slow?.value
        #expect(model.pager.items.map(\.ref.id) == [5])
    }

    @Test func aFailedNewTermDropsTheResultsOfThePreviousOne() async {
        let client = FakeCatalogClient()
        await client.set(search: { query, _ in
            if query == "arrakis" { throw CatalogFailure.network }
            return TitleList.sample([1, 2])
        })
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value

        model.query = "arrakis"
        await model.pending?.value
        #expect(model.pager.phase == .failed(.network))
        #expect(model.pager.items.isEmpty)
    }

    @Test func aNewTermWithoutResultsEmptiesTheList() async {
        let client = FakeCatalogClient()
        await client.set(search: { query, _ in
            query == "dune" ? TitleList.sample([1, 2]) : TitleList.sample([], totalPages: 0)
        })
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value

        model.query = "zzzz"
        await model.pending?.value
        #expect(model.pager.phase == .loaded)
        #expect(model.pager.items.isEmpty)
    }

    @Test func clearingTheFieldEmptiesTheResults() async {
        let client = FakeCatalogClient()
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value
        #expect(!model.pager.items.isEmpty)

        model.query = ""
        #expect(model.pager.phase == .idle)
        #expect(model.pager.items.isEmpty)
    }

    @Test func nextPagesLoadWhileScrolling() async {
        let client = FakeCatalogClient()
        await client.set(search: { _, page in
            page == 1
                ? TitleList.sample(Array(1...8), totalPages: 2)
                : TitleList.sample([9], totalPages: 2)
        })
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value
        model.pager.loadMoreIfNeeded(after: model.pager.items[7])
        await model.pager.nextPage?.value
        #expect(model.pager.items.map(\.ref.id) == Array(1...9))
        #expect(await client.searchCalls.map(\.page) == [1, 2])
    }

    @Test func aFailureIsShownAndCanBeRetried() async {
        let client = FakeCatalogClient()
        await client.set(search: { _, _ in throw CatalogFailure.network })
        let model = makeModel(client)
        model.query = "dune"
        await model.pending?.value
        #expect(model.pager.phase == .failed(.network))

        await client.set(search: { _, _ in TitleList.sample([5]) })
        await model.pager.retry()
        #expect(model.pager.phase == .loaded)
        #expect(model.pager.items.map(\.ref.id) == [5])
    }
}
