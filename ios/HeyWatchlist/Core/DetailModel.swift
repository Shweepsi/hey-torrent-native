import Foundation
import Observation

/// État de la fiche d'un titre. L'en-tête (affiche, nom, année) s'affiche tout
/// de suite avec ce que la liste connaît ; le reste arrive avec la fiche.
@MainActor
@Observable
final class DetailModel {
    /// Saison ouverte (séries) et ses épisodes.
    struct SeasonSelection: Identifiable, Equatable, Sendable {
        let entry: SeasonEntry
        var episodes: Loadable<SeasonEpisodes>

        var id: Int { entry.number }
    }

    /// Le titre tel que la liste le connaissait.
    let title: Title
    private(set) var state: Loadable<TitleSheet> = .loading
    /// Saison dont la feuille est ouverte ; `nil` la ferme.
    var season: SeasonSelection?

    private let client: any CatalogClient
    @ObservationIgnored private var started = false

    init(title: Title, client: any CatalogClient) {
        self.title = title
        self.client = client
    }

    var sheet: TitleSheet? { state.value }

    /// Le titre le plus complet connu : celui de la fiche une fois chargée.
    var shownTitle: Title { sheet?.title ?? title }

    /// Premier chargement : un retour sur la fiche (après une fiche similaire
    /// par exemple) ne la recharge pas.
    func start() async {
        guard !started else { return }
        started = true
        await load()
    }

    func retry() async {
        state = .loading
        await load()
    }

    func openSeason(_ entry: SeasonEntry) async {
        season = SeasonSelection(entry: entry, episodes: .loading)
        await loadSeason(entry)
    }

    func retrySeason() async {
        guard let entry = season?.entry else { return }
        season?.episodes = .loading
        await loadSeason(entry)
    }

    private func load() async {
        await finishingEvenIfCancelled {
            do {
                self.state = .loaded(try await self.client.sheet(of: self.title.ref))
            } catch {
                self.state = .failed(CatalogFailure(error))
            }
        }
    }

    private func loadSeason(_ entry: SeasonEntry) async {
        await finishingEvenIfCancelled {
            let result: Loadable<SeasonEpisodes>
            do {
                result = .loaded(try await self.client.season(of: self.title.ref.id, number: entry.number))
            } catch {
                result = .failed(CatalogFailure(error))
            }
            // La personne a pu fermer la feuille, ou en ouvrir une autre.
            guard self.season?.entry.number == entry.number else { return }
            self.season?.episodes = result
        }
    }
}
