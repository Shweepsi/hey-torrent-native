import Foundation
import Observation

/// Recherche instantanée : chaque frappe repart après un court délai
/// (debounce), la requête précédente est annulée, et une réponse arrivée trop
/// tard pour la frappe en cours n'est jamais affichée. Les résultats du terme
/// précédent restent à l'écran (`pager.phase` vaut alors `.loading`, l'interface
/// les estompe) jusqu'à la première page du nouveau terme : la liste ne se vide
/// pas à chaque lettre. Sans résultat à garder, l'interface montre un squelette.
@MainActor
@Observable
final class SearchModel {
    /// Résultats paginés ; `phase` vaut `.idle` tant que la requête est vide.
    let pager = TitlePager()

    private var text = ""
    private let client: any CatalogClient
    private let debounce: Duration
    /// Terme (sans espaces autour) que la liste montre ou charge.
    @ObservationIgnored private var term = ""
    /// Attente du délai puis chargement de la première page (utile aux tests).
    @ObservationIgnored private(set) var pending: Task<Void, Never>?

    /// `debounce` : délai de silence avant d'interroger le catalogue.
    init(client: any CatalogClient, debounce: Duration = .milliseconds(350)) {
        self.client = client
        self.debounce = debounce
    }

    /// Texte du champ de recherche.
    var query: String {
        get { text }
        set {
            guard newValue != text else { return }
            text = newValue
            restart()
        }
    }

    private func restart() {
        let newTerm = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Un espace en plus ou en moins ne relance rien.
        guard newTerm != term else { return }
        term = newTerm
        pending?.cancel()
        pending = nil
        // Terme vidé : la liste se vide. Sinon elle garde ses titres jusqu'à la nouvelle page.
        pager.reset(loading: !newTerm.isEmpty, keepingItems: !newTerm.isEmpty)
        guard !newTerm.isEmpty else { return }
        let client = client
        let wait = debounce
        pending = Task {
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled else { return }
            await self.pager.begin(keepingItems: true) { page in try await client.search(newTerm, page: page) }
        }
    }
}
