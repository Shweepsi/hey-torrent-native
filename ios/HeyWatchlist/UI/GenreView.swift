import SwiftUI

/// Titres d'un genre, des films ou des séries, en grille à défilement infini.
struct GenreView: View {
    @State private var model: GenreModel
    @State private var kind: TitleKind
    let zoom: Namespace.ID

    init(route: GenreRoute, client: any CatalogClient, zoom: Namespace.ID) {
        _model = State(initialValue: GenreModel(genre: route.genre, client: client))
        _kind = State(initialValue: .movie)
        self.zoom = zoom
    }

    var body: some View {
        ZStack {
            HeyBackground()
            ScrollView {
                VStack(spacing: 16) {
                    Picker("genre.kind", selection: $kind) {
                        Text("genre.kind.movies").tag(TitleKind.movie)
                        Text("genre.kind.series").tag(TitleKind.series)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                    if model.pager.isStale {
                        StaleBanner()
                    }
                    grid
                }
                .padding(.vertical, 12)
            }
            .scrollIndicators(.hidden)
        }
        .navigationTitle(Text(verbatim: model.genre.name))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.start() }
        .onChange(of: kind) { _, newKind in
            Task { await model.select(newKind) }
        }
    }

    @ViewBuilder
    private var grid: some View {
        switch model.pager.phase {
        case .idle, .loading:
            TitleGridSkeleton()
        case .failed(let failure):
            FailureCard(failure: failure) { Task { await model.retry() } }
                .padding(.horizontal, 16)
        case .loaded:
            if model.pager.items.isEmpty {
                empty
            } else {
                TitleGrid(pager: model.pager, context: "genre.\(model.genre.id).\(model.kind)", zoom: zoom)
            }
        }
    }

    /// Page vide. Côté séries, c'est le cas d'un genre de film sans équivalent
    /// chez les séries : le cœur répond par une page vide.
    @ViewBuilder
    private var empty: some View {
        switch model.kind {
        case .movie:
            ContentUnavailableView("genre.empty", systemImage: "film.stack")
        case .series:
            ContentUnavailableView("genre.noSeries", systemImage: "tv")
        }
    }
}
