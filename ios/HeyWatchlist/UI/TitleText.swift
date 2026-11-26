import Foundation

/// Textes que l'interface tire des types de domaine.

extension DiscoverShelf {
    var title: LocalizedStringResource {
        switch self {
        case .trending: "discover.shelf.trending"
        case .nowPlaying: "discover.shelf.nowPlaying"
        case .upcoming: "discover.shelf.upcoming"
        case .popularMovies: "discover.shelf.popularMovies"
        case .popularSeries: "discover.shelf.popularSeries"
        case .onTheAirSeries: "discover.shelf.onTheAirSeries"
        case .topRatedMovies: "discover.shelf.topRatedMovies"
        case .topRatedSeries: "discover.shelf.topRatedSeries"
        }
    }
}

extension TitleKind {
    var label: LocalizedStringResource {
        switch self {
        case .movie: "title.kind.movie"
        case .series: "title.kind.series"
        }
    }
}

extension Title {
    /// « Film · 2024 », sous l'affiche.
    var caption: String {
        var parts = [String(localized: kind.label)]
        if let year { parts.append(String(year)) }
        return parts.joined(separator: " · ")
    }

    /// Note affichable : rien tant que personne n'a voté.
    var ratingText: String? {
        voteCount > 0 && rating > 0 ? Formatting.rating(rating) : nil
    }
}

extension TitleSheet {
    /// « Film · 2024 · 1 h 52 » ou « Série · 2019 · 3 saisons · 24 épisodes ».
    var metaLine: String {
        var parts = [String(localized: title.kind.label)]
        if let year = title.year { parts.append(String(year)) }
        switch title.kind {
        case .movie:
            if let runtimeMinutes, runtimeMinutes > 0 {
                parts.append(Formatting.runtime(minutes: runtimeMinutes))
            }
        case .series:
            if let seasonsCount, seasonsCount > 0 {
                parts.append(String(localized: "detail.seasons \(seasonsCount)"))
            }
            if let episodesCount, episodesCount > 0 {
                parts.append(String(localized: "detail.episodes \(episodesCount)"))
            }
        }
        return parts.joined(separator: " · ")
    }
}

extension EpisodeEntry {
    /// « 12 mars 2025 · 45 min ».
    var metaLine: String {
        var parts: [String] = []
        if let airDate, let text = Formatting.date(airDate) { parts.append(text) }
        if let runtimeMinutes, runtimeMinutes > 0 { parts.append(Formatting.runtime(minutes: runtimeMinutes)) }
        return parts.joined(separator: " · ")
    }
}

extension SeasonEntry {
    /// « 10 épisodes · 12 mars 2025 ».
    var metaLine: String {
        var parts = [String(localized: "detail.episodes \(episodeCount)")]
        if let airDate, let text = Formatting.date(airDate) { parts.append(text) }
        return parts.joined(separator: " · ")
    }
}
