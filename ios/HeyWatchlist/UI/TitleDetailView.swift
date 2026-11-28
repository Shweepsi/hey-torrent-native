import SwiftUI

extension View {
    /// Les fiches s'ouvrent par un zoom depuis l'affiche touchée. À poser sur
    /// la racine d'une `NavigationStack` : toutes les affiches de la pile
    /// (rangées, grilles, titres similaires) mènent alors à la même fiche.
    func titleDestination(client: any CatalogClient, zoom: Namespace.ID) -> some View {
        navigationDestination(for: TitleRoute.self) { route in
            TitleDetailView(route: route, client: client, zoom: zoom)
                .navigationTransition(.zoom(sourceID: route.source, in: zoom))
        }
    }
}

/// Fiche plein écran d'un film ou d'une série : fond, affiche, titre, durée,
/// note, genres, synopsis, bande-annonce, plateformes en France, casting,
/// saisons et titres similaires, et l'ajout à la watchlist (une demande).
struct TitleDetailView: View {
    @State private var model: DetailModel
    let zoom: Namespace.ID

    @Environment(\.openURL) private var openURL

    init(route: TitleRoute, client: any CatalogClient, zoom: Namespace.ID) {
        _model = State(initialValue: DetailModel(title: route.title, client: client))
        self.zoom = zoom
    }

    var body: some View {
        ZStack {
            // Fond uni : le fond d'écran du titre s'y fond sans raccord visible.
            HeyTheme.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    backdrop
                    VStack(alignment: .leading, spacing: 24) {
                        header
                        // En échec, une série se demande entière ; pendant le chargement, le choix attend.
                        WatchlistSection(title: model.shownTitle, seasons: watchlistSeasons)
                        sections
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .padding(.bottom, 48)
                }
            }
            .scrollIndicators(.hidden)
            .ignoresSafeArea(edges: .top)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarVisibility(.hidden, for: .tabBar)
        .task { await model.start() }
        .sheet(item: $model.season) { selection in
            SeasonSheet(model: model, selection: selection)
        }
    }

    private var watchlistSeasons: [SeasonEntry]? {
        switch model.state {
        case .loading: nil
        case .failed: []
        case .loaded(let sheet): sheet.seasons
        }
    }

    // MARK: En-tête

    /// Fond d'écran du titre, fondu vers le fond de l'app : aucun texte dessus.
    /// Le fondu suit une courbe douce (plusieurs paliers) : un dégradé à deux
    /// points laisse voir une marche là où l'image finit.
    private var backdrop: some View {
        Color.clear
            .frame(height: 280)
            .overlay { RemoteImage(url: model.shownTitle.backdropURL) }
            .overlay {
                LinearGradient(
                    stops: [
                        .init(color: Color.black.opacity(0.45), location: 0),
                        .init(color: .clear, location: 0.3),
                        .init(color: HeyTheme.background.opacity(0.18), location: 0.5),
                        .init(color: HeyTheme.background.opacity(0.5), location: 0.68),
                        .init(color: HeyTheme.background.opacity(0.82), location: 0.84),
                        .init(color: HeyTheme.background, location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .clipped()
            .accessibilityHidden(true)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 16) {
            // L'affiche déborde sur le fond d'écran ; le texte reste sur le fond uni.
            PosterView(url: model.shownTitle.posterURL)
                .frame(width: 120)
                .padding(.top, -80)
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: model.shownTitle.name)
                    .font(.title.bold())
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                if let tagline = model.sheet?.tagline {
                    Text(verbatim: tagline)
                        .font(.subheadline.italic())
                        .foregroundStyle(.secondary)
                }
                Text(verbatim: model.sheet?.metaLine ?? model.title.caption)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let rating = model.shownTitle.ratingText {
                    Label {
                        Text(verbatim: rating)
                    } icon: {
                        Image(systemName: "star.fill").foregroundStyle(.yellow)
                    }
                    .font(.subheadline.weight(.semibold))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text("detail.rating \(rating)"))
                }
                if let genres = model.sheet?.genres, !genres.isEmpty {
                    Text(verbatim: genres.map(\.name).joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: Contenu

    @ViewBuilder
    private var sections: some View {
        switch model.state {
        case .loading:
            if model.title.overview.isEmpty {
                TextSkeleton(lines: 4)
            } else {
                synopsis(model.title.overview)
            }
            SkeletonBlock(cornerRadius: 16).frame(height: 56)
            SkeletonBlock(cornerRadius: 16).frame(height: 96)
        case .failed(let failure):
            if !model.title.overview.isEmpty {
                synopsis(model.title.overview)
            }
            FailureCard(failure: failure) { Task { await model.retry() } }
        case .loaded(let sheet):
            loaded(sheet)
        }
    }

    @ViewBuilder
    private func loaded(_ sheet: TitleSheet) -> some View {
        if sheet.stale {
            StaleBanner()
        }
        if let trailer = sheet.trailerURL {
            Button {
                openURL(trailer)
            } label: {
                Label("detail.trailer", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .controlSize(.large)
            .accessibilityHint(Text("detail.trailer.hint"))
        }
        if sheet.title.overview.isEmpty {
            Text("detail.overview.none").foregroundStyle(.secondary)
        } else {
            synopsis(sheet.title.overview)
        }
        if !sheet.directors.isEmpty {
            credits(of: sheet)
        }
        if !sheet.cast.isEmpty {
            CastSection(cast: sheet.cast)
        }
        if !sheet.seasons.isEmpty {
            SeasonsSection(seasons: sheet.seasons) { entry in
                Task { await model.openSeason(entry) }
            }
        }
        if !sheet.similar.isEmpty {
            SimilarSection(titles: sheet.similar, parent: sheet.title.ref, zoom: zoom)
        }
    }

    private func synopsis(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader("detail.overview")
            Text(verbatim: text)
        }
    }

    private func credits(of sheet: TitleSheet) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            switch sheet.title.kind {
            case .movie:
                Text("detail.directors.movie").font(.subheadline).foregroundStyle(.secondary)
            case .series:
                Text("detail.directors.series").font(.subheadline).foregroundStyle(.secondary)
            }
            Text(verbatim: sheet.directors.joined(separator: ", ")).font(.body.weight(.semibold))
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: Sections

private struct SectionHeader: View {
    let key: LocalizedStringKey

    init(_ key: LocalizedStringKey) {
        self.key = key
    }

    var body: some View {
        Text(key)
            .font(.title3.bold())
            .accessibilityAddTraits(.isHeader)
    }
}

private struct CastSection: View {
    let cast: [CastPerson]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader("detail.cast")
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(Array(cast.enumerated()), id: \.offset) { _, person in
                        CastCard(person: person)
                    }
                }
                .padding(.horizontal, 20)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -20)
        }
    }
}

private struct CastCard: View {
    let person: CastPerson

    var body: some View {
        VStack(spacing: 6) {
            RemoteImage(url: person.profileURL, fallbackSymbol: "person.fill")
                .frame(width: 76, height: 76)
                .clipShape(Circle())
            Text(verbatim: person.name)
                .font(.caption.weight(.semibold))
                .lineLimit(2)
                .multilineTextAlignment(.center)
            if let character = person.character {
                Text(verbatim: character)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(width: 88)
        .accessibilityElement(children: .combine)
    }
}

private struct SeasonsSection: View {
    let seasons: [SeasonEntry]
    let onSelect: (SeasonEntry) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader("detail.seasons.title")
            VStack(spacing: 10) {
                ForEach(seasons) { season in
                    Button { onSelect(season) } label: { SeasonRow(season: season) }
                        .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct SeasonRow: View {
    let season: SeasonEntry

    var body: some View {
        HStack(spacing: 14) {
            RemoteImage(url: season.posterURL, fallbackSymbol: "tv")
                .frame(width: 48, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: season.name).font(.headline)
                Text(verbatim: season.metaLine).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

private struct SimilarSection: View {
    let titles: [Title]
    let parent: TitleRef
    let zoom: Namespace.ID

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader("detail.similar")
            PosterRow(titles: titles, context: "similar.\(parent.kind).\(parent.id)", zoom: zoom)
                .padding(.horizontal, -20)
        }
    }
}
