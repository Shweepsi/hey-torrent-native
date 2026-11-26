import Foundation

/// État d'un contenu qui se charge : chaque rangée, fiche ou saison a le sien,
/// pour qu'un échec n'en bloque pas un autre.
enum Loadable<Value: Equatable & Sendable>: Equatable, Sendable {
    case loading
    case loaded(Value)
    case failed(CatalogFailure)

    var value: Value? {
        switch self {
        case .loaded(let value): return value
        case .loading, .failed: return nil
        }
    }
}

/// Mène `work` à son terme même si la tâche de la vue qui l'attend est
/// annulée (changement d'onglet, ouverture d'une fiche, fin du geste de
/// rafraîchissement) : l'appel du cœur n'est pas interrompu à mi-chemin.
@MainActor
func finishingEvenIfCancelled(_ work: @escaping @MainActor () async -> Void) async {
    await Task { await work() }.value
}
