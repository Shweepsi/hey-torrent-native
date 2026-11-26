import SwiftUI

/// Grille d'affiches à défilement infini (recherche, genre) : la page suivante
/// part quand l'une des dernières affiches apparaît. À poser dans un `ScrollView`.
struct TitleGrid: View {
    let pager: TitlePager
    let context: String
    let zoom: Namespace.ID

    var body: some View {
        VStack(spacing: 20) {
            LazyVGrid(columns: TitleGrid.columns, alignment: .leading, spacing: 20) {
                ForEach(pager.items) { title in
                    PosterCard(title: title, context: context, zoom: zoom)
                        .onAppear { pager.loadMoreIfNeeded(after: title) }
                }
            }
            if pager.isLoadingMore {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .accessibilityLabel(Text("common.loading"))
            } else if let failure = pager.moreFailure {
                FailureCard(failure: failure) { pager.retryMore() }
            }
        }
        .padding(.horizontal, 16)
    }

    static var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 104, maximum: 170), spacing: 14, alignment: .top)]
    }
}

/// Grille de squelettes, le temps de la première page.
struct TitleGridSkeleton: View {
    var body: some View {
        LazyVGrid(columns: TitleGrid.columns, alignment: .leading, spacing: 20) {
            ForEach(0..<9, id: \.self) { _ in
                PosterSkeleton()
            }
        }
        .padding(.horizontal, 16)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("common.loading"))
    }
}
