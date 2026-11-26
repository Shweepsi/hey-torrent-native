import SwiftUI

/// Onglet Watchlist : mes titres et l'état de chaque demande, la plus récente
/// d'abord. Une ligne ouvre la fiche, où l'on change les saisons ou retire le titre.
struct WatchlistView: View {
    let catalog: any CatalogClient

    @Environment(WatchlistModel.self) private var watchlist
    @Namespace private var zoom

    var body: some View {
        NavigationStack {
            ZStack {
                HeyBackground()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        if watchlist.offline {
                            StaleBanner(text: "watchlist.offline")
                        }
                        content
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("tab.watchlist")
            .refreshable { await watchlist.refresh() }
            .titleDestination(client: catalog, zoom: zoom)
        }
    }

    @ViewBuilder
    private var content: some View {
        if !watchlist.loaded {
            ProgressView().frame(maxWidth: .infinity).padding(32)
        } else if watchlist.entries.isEmpty {
            Text("watchlist.empty")
                .foregroundStyle(.secondary)
                .padding(24)
                .frame(maxWidth: .infinity)
                .glassEffect(.regular, in: .rect(cornerRadius: 28))
        } else {
            ForEach(watchlist.entries) { entry in
                let route = TitleRoute(entry.title, in: "watchlist")
                NavigationLink(value: route) {
                    WatchlistRow(entry: entry)
                        .matchedTransitionSource(id: route.source, in: zoom)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Une ligne : vignette, titre, type et année, saisons, état de la demande.
private struct WatchlistRow: View {
    let entry: WatchlistEntry

    var body: some View {
        HStack(spacing: 14) {
            PosterView(url: entry.posterURL)
                .frame(width: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: entry.name)
                    .font(.headline)
                    .lineLimit(2)
                Text(verbatim: entry.title.caption)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let seasons = entry.seasonsText {
                    seasons.font(.footnote).foregroundStyle(.secondary)
                }
                entry.stateText
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(entry.stateColor)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
