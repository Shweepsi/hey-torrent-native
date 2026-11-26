import SwiftUI

// Textes de la watchlist, construits à partir des clés de Localizable.xcstrings.

extension WatchlistEntry {
    /// Nom court de l'état de la demande.
    var stateText: Text {
        if pending { return Text("watchlist.state.pending") }
        switch state {
        case .requested: return Text("watchlist.state.requested")
        case .inProgress: return Text("watchlist.state.inProgress")
        case .available: return Text("watchlist.state.available")
        case .refused:
            if let refusalReason { return Text("watchlist.state.refused.reason \(refusalReason)") }
            return Text("watchlist.state.refused")
        }
    }

    /// Couleur de l'état : un repère en plus du texte, jamais seule.
    var stateColor: Color {
        if pending { return .secondary }
        switch state {
        case .available: return HeyTheme.primary
        case .refused: return .red
        case .requested, .inProgress: return .primary
        }
    }

    /// « Série entière », « Saisons 1, 3 et épisodes spéciaux » ; `nil` pour un film.
    var seasonsText: Text? {
        guard ref.kind == .series else { return nil }
        guard let seasons else { return Text("watchlist.wholeSeries") }
        let numbered = seasons.filter { $0 > 0 }
        let list = numbered.map(String.init).joined(separator: ", ")
        if numbered.isEmpty { return Text("watchlist.specials") }
        // Une seule saison ou plusieurs : deux textes, sans règle de pluriel à deux nombres.
        switch (numbered.count == 1, seasons.contains(0)) {
        case (true, false): return Text("watchlist.season.one \(list)")
        case (false, false): return Text("watchlist.seasons.many \(list)")
        case (true, true): return Text("watchlist.season.one.specials \(list)")
        case (false, true): return Text("watchlist.seasons.many.specials \(list)")
        }
    }
}
