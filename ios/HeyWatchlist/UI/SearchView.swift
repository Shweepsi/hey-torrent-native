import SwiftUI

/// Onglet Rechercher (bulle séparée de la barre d'onglets, iOS 26) : les
/// résultats se mettent à jour pendant la frappe.
struct SearchView: View {
    @Bindable var model: SearchModel
    let catalog: any CatalogClient

    @Namespace private var zoom

    var body: some View {
        NavigationStack {
            ZStack {
                HeyBackground()
                content
            }
            .navigationTitle("tab.search")
            .searchable(text: $model.query, prompt: Text("search.prompt"))
            .titleDestination(client: catalog, zoom: zoom)
        }
    }

    @ViewBuilder
    private var content: some View {
        let pager = model.pager
        switch pager.phase {
        case .idle:
            ContentUnavailableView(
                "search.idle.title",
                systemImage: "magnifyingglass",
                description: Text("search.idle.description")
            )
        case .failed(let failure):
            ScrollView {
                FailureCard(failure: failure) { Task { await model.pager.retry() } }
                    .padding(16)
            }
        case .loading, .loaded:
            if !pager.items.isEmpty {
                // Pendant la frappe, les résultats du terme précédent restent,
                // estompés, jusqu'à la première page du nouveau terme.
                results.opacity(pager.phase == .loading ? 0.4 : 1)
            } else if pager.phase == .loading {
                ScrollView {
                    TitleGridSkeleton().padding(.vertical, 12)
                }
                .scrollIndicators(.hidden)
            } else {
                ContentUnavailableView {
                    Label("search.empty.title", systemImage: "magnifyingglass")
                } description: {
                    Text("search.empty.description \(model.query.trimmingCharacters(in: .whitespacesAndNewlines))")
                }
            }
        }
    }

    private var results: some View {
        ScrollView {
            TitleGrid(pager: model.pager, context: "search", zoom: zoom)
                .padding(.vertical, 12)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
    }
}
