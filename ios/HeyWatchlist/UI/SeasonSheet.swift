import SwiftUI

/// Feuille des épisodes d'une saison, ouverte depuis la fiche d'une série.
struct SeasonSheet: View {
    let model: DetailModel
    let selection: DetailModel.SeasonSelection

    var body: some View {
        NavigationStack {
            ZStack {
                HeyBackground()
                ScrollView {
                    content
                        .padding(20)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle(Text(verbatim: selection.entry.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.close") { model.season = nil }
                }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch selection.episodes {
        case .loading:
            VStack(alignment: .leading, spacing: 24) {
                ForEach(0..<4, id: \.self) { _ in
                    HStack(alignment: .top, spacing: 12) {
                        SkeletonBlock(cornerRadius: 10).frame(width: 128, height: 72)
                        TextSkeleton(lines: 3)
                    }
                }
            }
        case .failed(let failure):
            FailureCard(failure: failure) { Task { await model.retrySeason() } }
        case .loaded(let season):
            LazyVStack(alignment: .leading, spacing: 20) {
                if season.stale {
                    StaleBanner()
                }
                if !season.overview.isEmpty {
                    Text(verbatim: season.overview).foregroundStyle(.secondary)
                }
                if season.episodes.isEmpty {
                    Text("detail.season.empty").foregroundStyle(.secondary)
                }
                ForEach(season.episodes) { episode in
                    EpisodeRow(episode: episode)
                }
            }
        }
    }
}

private struct EpisodeRow: View {
    let episode: EpisodeEntry

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RemoteImage(url: episode.stillURL, fallbackSymbol: "tv")
                .frame(width: 128, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: "\(episode.number). \(episode.name)")
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                if !episode.metaLine.isEmpty || episode.rating > 0 {
                    HStack(spacing: 8) {
                        if !episode.metaLine.isEmpty {
                            Text(verbatim: episode.metaLine)
                        }
                        if episode.rating > 0 {
                            let rating = Formatting.rating(episode.rating)
                            Label {
                                Text(verbatim: rating)
                            } icon: {
                                Image(systemName: "star.fill")
                            }
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(Text("detail.rating \(rating)"))
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                if !episode.overview.isEmpty {
                    Text(verbatim: episode.overview)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}
