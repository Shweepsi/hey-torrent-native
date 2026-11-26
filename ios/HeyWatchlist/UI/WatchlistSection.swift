import SwiftUI

/// Ajout à la watchlist sur la fiche : un bouton tant que le titre n'y est pas,
/// puis l'état de la demande. Une série se demande entière ou par saisons.
///
/// `seasons` : saisons de la fiche, `nil` tant qu'elle se charge (le choix des
/// saisons attend).
struct WatchlistSection: View {
    let title: Title
    let seasons: [SeasonEntry]?

    @Environment(WatchlistModel.self) private var watchlist
    @State private var picking = false

    private var entry: WatchlistEntry? { watchlist.entry(for: title.ref) }
    private var busy: Bool { watchlist.busy.contains(title.ref) }
    private var isSeries: Bool { title.kind == .series }
    private var choices: [SeasonEntry] { seasons ?? [] }

    var body: some View {
        Group {
            if let entry {
                inWatchlist(entry)
            } else {
                Button {
                    // Une série sans saison connue se demande entière.
                    if isSeries && !choices.isEmpty {
                        picking = true
                    } else {
                        Task { await watchlist.add(title, seasons: nil) }
                    }
                } label: {
                    Label("watchlist.add", systemImage: "plus")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .disabled(busy || (isSeries && seasons == nil))
            }
        }
        .sheet(isPresented: $picking) {
            SeasonPickerSheet(seasons: choices, initial: entry?.seasons) { chosen in
                picking = false
                Task { await watchlist.add(title, seasons: chosen) }
            }
        }
    }

    private func inWatchlist(_ entry: WatchlistEntry) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(HeyTheme.primary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("watchlist.inList").font(.headline)
                    entry.stateText.font(.subheadline).foregroundStyle(entry.stateColor)
                    if let seasons = entry.seasonsText {
                        seasons.font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: 0)
            }
            if isSeries && !choices.isEmpty {
                Button {
                    picking = true
                } label: {
                    Text("watchlist.editSeasons").frame(maxWidth: .infinity)
                }
                .buttonStyle(.glass)
                .disabled(busy)
            }
            Button(role: .destructive) {
                Task { await watchlist.remove(title.ref) }
            } label: {
                Text("watchlist.remove").frame(maxWidth: .infinity)
            }
            .buttonStyle(.glass)
            .disabled(busy)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Fond uni : la fiche défile, pas de verre en mouvement (docs/DESIGN.md).
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// Choix entre la série entière et des saisons précises. `initial` : saisons
/// déjà demandées (`nil` : série entière, ou première demande).
private struct SeasonPickerSheet: View {
    let seasons: [SeasonEntry]
    let onSubmit: ([Int]?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var whole: Bool
    @State private var chosen: Set<Int>

    init(seasons: [SeasonEntry], initial: [Int]?, onSubmit: @escaping ([Int]?) -> Void) {
        self.seasons = seasons
        self.onSubmit = onSubmit
        _whole = State(initialValue: initial == nil)
        _chosen = State(initialValue: Set(initial ?? []))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("watchlist.wholeSeries", isOn: Binding(
                        get: { whole },
                        set: { on in
                            whole = on
                            if on { chosen = [] }
                        }
                    ))
                }
                Section("detail.seasons.title") {
                    ForEach(seasons) { season in
                        Toggle(isOn: Binding(
                            get: { !whole && chosen.contains(season.number) },
                            set: { on in
                                whole = false
                                if on { chosen.insert(season.number) } else { chosen.remove(season.number) }
                            }
                        )) {
                            Text(verbatim: season.name)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(HeyBackground())
            .navigationTitle("watchlist.chooseSeasons")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("watchlist.cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("watchlist.request") { onSubmit(whole ? nil : chosen.sorted()) }
                        .disabled(!whole && chosen.isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
