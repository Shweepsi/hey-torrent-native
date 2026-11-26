import Foundation
import Testing

@testable import HeyWatchlist

@MainActor
struct GenreModelTests {
    private let action = TitleGenre(id: 28, name: "Action")

    @Test func startLoadsTheFirstPageOfMovies() async {
        let client = FakeCatalogClient()
        let model = GenreModel(genre: action, client: client)
        await model.start()
        #expect(model.kind == .movie)
        #expect(model.pager.phase == .loaded)
        #expect(model.pager.items.map(\.ref.id) == [1, 2, 3])
        #expect(await client.titlesCalls == [TitlesCall(kind: .movie, genre: 28, page: 1)])
    }

    @Test func comingBackToTheScreenDoesNotReloadIt() async {
        let client = FakeCatalogClient()
        let model = GenreModel(genre: action, client: client)
        await model.start()
        await model.start()
        #expect(await client.titlesCalls.count == 1)
    }

    /// La correspondance film → série est une règle du cœur : l'interface passe
    /// l'identifiant du genre de film tel quel et ne lit aucune liste de genres.
    @Test func switchingToSeriesSendsTheMovieGenreIdAsIs() async {
        let client = FakeCatalogClient()
        let model = GenreModel(genre: action, client: client)
        await model.start()
        await model.select(.series)
        #expect(model.kind == .series)
        #expect(model.pager.phase == .loaded)
        #expect(await client.titlesCalls.last == TitlesCall(kind: .series, genre: 28, page: 1))
        #expect(await client.genreCalls.isEmpty)
    }

    @Test func anEmptySeriesPageIsAnEmptyListNotAnError() async {
        let client = FakeCatalogClient()
        await client.set(titles: { kind, _, _ in
            kind == .series ? TitleList.sample([], totalPages: 0) : TitleList.sample([1, 2, 3])
        })
        let model = GenreModel(genre: TitleGenre(id: 27, name: "Horreur"), client: client)
        await model.start()
        await model.select(.series)
        #expect(model.pager.phase == .loaded)
        #expect(model.pager.items.isEmpty)
        #expect(!model.pager.hasMore)
        #expect(await client.titlesCalls.map(\.genre) == [27, 27])
    }

    @Test func backToMoviesReplacesTheEmptySeriesPage() async {
        let client = FakeCatalogClient()
        await client.set(titles: { kind, _, _ in
            kind == .series ? TitleList.sample([], totalPages: 0) : TitleList.sample([1, 2, 3])
        })
        let model = GenreModel(genre: action, client: client)
        await model.start()
        await model.select(.series)
        await model.select(.movie)
        #expect(model.kind == .movie)
        #expect(model.pager.items.map(\.ref.id) == [1, 2, 3])
        #expect(await client.titlesCalls.last == TitlesCall(kind: .movie, genre: 28, page: 1))
    }

    @Test func anObsoleteSeriesPageIsIgnored() async {
        let client = FakeCatalogClient()
        let started = Gate()
        let release = Gate()
        await client.set(titles: { kind, _, _ in
            guard kind == .series else { return TitleList.sample([1, 2, 3]) }
            await started.open()
            await release.wait()
            return TitleList.sample([9], kind: .series)
        })
        let model = GenreModel(genre: action, client: client)
        await model.start()
        let slow = Task { await model.select(.series) }
        await started.wait()

        await model.select(.movie)
        await release.open()
        await slow.value

        #expect(model.kind == .movie)
        #expect(model.pager.phase == .loaded)
        #expect(model.pager.items.map(\.ref.id) == [1, 2, 3])
    }

    @Test func choosingTheSameKindAgainDoesNothing() async {
        let client = FakeCatalogClient()
        let model = GenreModel(genre: action, client: client)
        await model.start()
        await model.select(.movie)
        #expect(await client.titlesCalls.count == 1)
    }

    @Test func nextPagesLoadWhileScrolling() async {
        let client = FakeCatalogClient()
        await client.set(titles: { _, _, page in
            page == 1
                ? TitleList.sample(Array(1...8), totalPages: 2)
                : TitleList.sample([9], totalPages: 2)
        })
        let model = GenreModel(genre: action, client: client)
        await model.start()
        model.pager.loadMoreIfNeeded(after: model.pager.items[7])
        await model.pager.nextPage?.value
        #expect(model.pager.items.count == 9)
        #expect(await client.titlesCalls.map(\.page) == [1, 2])
    }

    @Test func aFailureCanBeRetried() async {
        let client = FakeCatalogClient()
        await client.set(titles: { _, _, _ in throw CatalogFailure.server })
        let model = GenreModel(genre: action, client: client)
        await model.start()
        #expect(model.pager.phase == .failed(.server))

        await client.set(titles: { _, _, _ in TitleList.sample([8]) })
        await model.retry()
        #expect(model.pager.items.map(\.ref.id) == [8])
    }
}
