import Foundation

// Types de domaine du catalogue. Les types exportés par le cœur (TitleSummary,
// Genre, Episode…) sont compilés dans le même module que l'app : les nôtres
// portent d'autres noms, et seul `LiveCatalogClient` connaît les premiers.

/// Film ou série.
enum TitleKind: Hashable, Sendable {
    case movie
    case series
}

/// Une rangée de l'écran Découvrir, dans l'ordre où elles s'affichent.
enum DiscoverShelf: CaseIterable, Hashable, Sendable {
    /// Films et séries les plus regardés de la semaine ; sert aussi au carrousel.
    case trending
    case nowPlaying
    case upcoming
    case popularMovies
    case popularSeries
    case onTheAirSeries
    case topRatedMovies
    case topRatedSeries
}

/// Identité d'un titre : un film et une série peuvent avoir le même numéro.
struct TitleRef: Hashable, Sendable {
    let kind: TitleKind
    let id: Int
}

/// Un titre dans une liste (affiche, nom, année, note).
struct Title: Identifiable, Hashable, Sendable {
    let ref: TitleRef
    let name: String
    let year: Int?
    let overview: String
    let posterURL: URL?
    let backdropURL: URL?
    /// Note moyenne sur 10, sans valeur tant que `voteCount` vaut 0.
    let rating: Double
    let voteCount: Int

    var id: TitleRef { ref }
    var kind: TitleKind { ref.kind }
}

/// Une page de titres. `stale` : lue dans le cache faute de réseau.
struct TitleList: Equatable, Sendable {
    let items: [Title]
    let totalPages: Int
    let stale: Bool
    /// Page vraiment rendue : le cœur saute les pages que le tri par langue
    /// vide. `nil` : celle demandée.
    var page: Int? = nil
}

struct TitleGenre: Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
}

struct CastPerson: Equatable, Sendable {
    let name: String
    let character: String?
    let profileURL: URL?
}

struct SeasonEntry: Identifiable, Equatable, Sendable {
    let number: Int
    let name: String
    let episodeCount: Int
    /// `AAAA-MM-JJ`.
    let airDate: String?
    let posterURL: URL?

    var id: Int { number }
}

/// Fiche complète d'un film ou d'une série.
struct TitleSheet: Equatable, Sendable {
    let title: Title
    let tagline: String?
    let genres: [TitleGenre]
    /// Film : durée ; série : durée d'un épisode.
    let runtimeMinutes: Int?
    let seasonsCount: Int?
    let episodesCount: Int?
    let seasons: [SeasonEntry]
    let cast: [CastPerson]
    /// Réalisateurs (film) ou créateurs (série).
    let directors: [String]
    /// Bande-annonce à ouvrir dans YouTube ou le navigateur : à défaut, un
    /// teaser ; jamais un extrait ni un making-of. `nil` : pas de bouton.
    let trailerURL: URL?
    let similar: [Title]
    let stale: Bool
}

struct EpisodeEntry: Identifiable, Equatable, Sendable {
    let number: Int
    let name: String
    let overview: String
    /// `AAAA-MM-JJ`.
    let airDate: String?
    let runtimeMinutes: Int?
    let stillURL: URL?
    /// Sur 10, sans valeur à 0.
    let rating: Double

    var id: Int { number }
}

struct SeasonEpisodes: Equatable, Sendable {
    let number: Int
    let name: String
    let overview: String
    let episodes: [EpisodeEntry]
    let stale: Bool
}

/// Seul point d'entrée de l'interface vers le catalogue géré par le cœur.
///
/// Les appels se font hors du fil principal ; l'implémentation réelle
/// (`LiveCatalogClient`) parle à Rust, les tests utilisent une doublure.
/// Chaque erreur levée est une `CatalogFailure`. L'annulation de la tâche
/// appelante n'interrompt pas l'appel : les modèles écartent la réponse d'une
/// requête remplacée (`TitlePager`).
protocol CatalogClient: Sendable {
    /// Une page d'une rangée de Découvrir (pages à partir de 1).
    func shelf(_ shelf: DiscoverShelf, page: Int) async throws -> TitleList
    func genres(of kind: TitleKind) async throws -> [TitleGenre]
    /// Titres d'un genre, du plus populaire au moins populaire. `genre` est
    /// toujours un identifiant de genre de *film* (`genres(of: .movie)`) : pour
    /// les séries, le cœur le traduit, et un genre sans équivalent chez les
    /// séries donne une page vide.
    func titles(of kind: TitleKind, genre: Int, page: Int) async throws -> TitleList
    /// Recherche de films et de séries. Requête vide : page vide, sans réseau.
    func search(_ query: String, page: Int) async throws -> TitleList
    func sheet(of ref: TitleRef) async throws -> TitleSheet
    func season(of series: Int, number: Int) async throws -> SeasonEpisodes
}
