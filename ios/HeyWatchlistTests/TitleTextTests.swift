import Foundation
import Testing

@testable import HeyWatchlist

struct TitleTextTests {
    @Test func aCaptionNamesTheKindAndTheYear() {
        #expect(Title.sample(1).caption == "Film · 2020")
        #expect(Title.sample(1, kind: .series).caption == "Série · 2020")
    }

    @Test func aTitleNobodyVotedForHasNoRating() {
        let unrated = Title(
            ref: TitleRef(kind: .movie, id: 1), name: "Nouveau", year: nil, overview: "",
            posterURL: nil, backdropURL: nil, rating: 0, voteCount: 0
        )
        #expect(unrated.ratingText == nil)
        #expect(unrated.caption == "Film")
        #expect(Title.sample(1).ratingText == "7,5")
    }

    @Test func aMovieSheetShowsItsRuntime() {
        #expect(TitleSheet.sample(TitleRef(kind: .movie, id: 5)).metaLine == "Film · 2020 · 1 h 52")
    }

    @Test func aSeriesSheetShowsItsSeasons() {
        let sheet = TitleSheet.sample(TitleRef(kind: .series, id: 5), seasons: [.sample(1), .sample(2)])
        #expect(sheet.metaLine == "Série · 2020 · 2 saisons")
    }

    @Test func episodesAndSeasonsDescribeThemselves() {
        #expect(SeasonEntry.sample(1).metaLine == "2 épisodes · 12 avril 2019")
        #expect(SeasonEpisodes.sample(1).episodes.map(\.metaLine) == ["12 avril 2019 · 45 min", ""])
    }

    /// Une clé absente du catalogue de textes s'afficherait telle quelle.
    @Test func everyShelfHasAFrenchTitle() {
        let titles = DiscoverShelf.allCases.map { String(localized: $0.title) }
        #expect(Set(titles).count == DiscoverShelf.allCases.count)
        #expect(titles.allSatisfy { !$0.hasPrefix("discover.shelf.") })
    }
}
