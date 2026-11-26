import SwiftUI

/// Genre choisi dans les pastilles de Découvrir.
struct GenreRoute: Hashable {
    let genre: TitleGenre
}

/// Onglet Découvrir : carrousel, pastilles de genres, puis une rangée par
/// liste du catalogue. Chaque rangée se charge, ou échoue, seule.
struct DiscoverView: View {
    let model: DiscoverModel
    let catalog: any CatalogClient

    @Namespace private var zoom

    var body: some View {
        NavigationStack {
            ZStack {
                HeyBackground()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 28) {
                        if model.isStale {
                            StaleBanner()
                        }
                        hero
                        genreSection
                        ForEach(DiscoverShelf.allCases, id: \.self) { shelf in
                            ShelfRow(
                                shelf: shelf,
                                state: model.state(of: shelf),
                                zoom: zoom,
                                retry: { Task { await model.retry(shelf) } }
                            )
                        }
                    }
                    .padding(.vertical, 12)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("tab.discover")
            .refreshable { await model.refresh() }
            .task { await model.start() }
            .titleDestination(client: catalog, zoom: zoom)
            .navigationDestination(for: GenreRoute.self) { route in
                GenreView(route: route, client: catalog, zoom: zoom)
            }
        }
    }

    @ViewBuilder
    private var hero: some View {
        switch model.state(of: .trending) {
        case .loading:
            HeroSkeleton()
        case .loaded:
            if !model.heroTitles.isEmpty {
                HeroCarousel(titles: model.heroTitles, zoom: zoom)
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel(Text("discover.hero"))
            }
        case .failed:
            // L'échec s'affiche une fois, dans la rangée « Tendances ».
            EmptyView()
        }
    }

    private var genreSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("discover.genres")
                .font(.title2.bold())
                .padding(.horizontal, 20)
                .accessibilityAddTraits(.isHeader)
            switch model.genres {
            case .loading:
                SkeletonBlock(cornerRadius: 22)
                    .frame(height: 44)
                    .padding(.horizontal, 20)
            case .loaded(let genres):
                GenrePills(genres: genres)
            case .failed(let failure):
                FailureCard(failure: failure) { Task { await model.retryGenres() } }
                    .padding(.horizontal, 20)
            }
        }
    }
}

/// Une rangée : son titre, puis ses affiches, son squelette ou son échec.
private struct ShelfRow: View {
    let shelf: DiscoverShelf
    let state: Loadable<TitleList>
    let zoom: Namespace.ID
    let retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(shelf.title)
                .font(.title2.bold())
                .padding(.horizontal, 20)
                .accessibilityAddTraits(.isHeader)
            switch state {
            case .loading:
                PosterRowSkeleton()
            case .loaded(let list):
                if list.items.isEmpty {
                    Text("discover.shelf.empty")
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 20)
                } else {
                    PosterRow(titles: list.items, context: "shelf.\(shelf)", zoom: zoom)
                }
            case .failed(let failure):
                FailureCard(failure: failure, retry: retry)
                    .padding(.horizontal, 20)
            }
        }
    }
}

/// Pastilles de genres de films. Un fond uni, pas de verre : elles défilent.
private struct GenrePills: View {
    let genres: [TitleGenre]

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 10) {
                ForEach(genres) { genre in
                    NavigationLink(value: GenreRoute(genre: genre)) {
                        Text(verbatim: genre.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .padding(.horizontal, 18)
                            .frame(minHeight: 44)
                            .background(Color.white.opacity(0.1), in: Capsule())
                            .overlay { Capsule().strokeBorder(Color.white.opacity(0.2), lineWidth: 1) }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
        }
        .scrollIndicators(.hidden)
    }
}
