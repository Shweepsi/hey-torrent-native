import Foundation
import Observation

/// Écran d'un genre : une grille paginée à l'infini, des films ou des séries.
///
/// Le genre vient des pastilles de Découvrir : c'est toujours un genre de
/// *film*, et l'identifiant part tel quel au catalogue, pour les séries aussi.
/// TMDB numérote à part les genres des séries ; c'est le cœur qui fait la
/// correspondance. Un genre sans équivalent chez les séries (« Horreur ») donne
/// une page vide, que l'écran présente comme « aucun résultat ».
@MainActor
@Observable
final class GenreModel {
    let genre: TitleGenre
    private(set) var kind: TitleKind = .movie
    let pager = TitlePager()

    private let client: any CatalogClient
    @ObservationIgnored private var started = false

    init(genre: TitleGenre, client: any CatalogClient) {
        self.genre = genre
        self.client = client
    }

    /// Premier chargement ; un retour sur l'écran (après une fiche) ne recharge rien.
    func start() async {
        guard !started else { return }
        started = true
        await load()
    }

    /// Passe des films aux séries (ou l'inverse) : recommence à la première page.
    func select(_ newKind: TitleKind) async {
        guard newKind != kind else { return }
        kind = newKind
        await load()
    }

    /// Recommence après un échec de la première page.
    func retry() async {
        await pager.retry()
    }

    private func load() async {
        let client = self.client
        let kind = self.kind
        let genreID = genre.id
        await pager.begin { page in
            try await client.titles(of: kind, genre: genreID, page: page)
        }
    }
}
