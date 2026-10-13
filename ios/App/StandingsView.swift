import SwiftUI

/// Conference standings from NCAA.com, one division at a time, optionally narrowed to one conference.
struct StandingsView: View {
    @EnvironmentObject private var store: ScoreStore
    @State private var division = "d1"
    @State private var conference = ""
    @State private var standings: Standings?
    @State private var loading = false
    @State private var error: String?

    private var shown: [Standings.Conference] {
        (standings?.conferences ?? []).filter { conference.isEmpty || $0.name == conference }
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Picker("Division", selection: $division) {
                            Text("Division I").tag("d1"); Text("Division II").tag("d2"); Text("Division III").tag("d3")
                        }
                        Spacer()
                        Picker("Conference", selection: $conference) {
                            Text("All conferences").tag("")
                            ForEach(standings?.conferences ?? []) { Text($0.name).tag($0.name) }
                        }
                    }.pickerStyle(.menu).labelsHidden().listRowBackground(Color.clear).listRowInsets(EdgeInsets())
                }
                ForEach(shown) { group in
                    Section {
                        header(group)
                        ForEach(Array(group.teams.enumerated()), id: \.element.id) { index, row in
                            HStack(spacing: 10) {
                                Text("\(index + 1)").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 20, alignment: .trailing)
                                Text(row.school).font(.subheadline.weight(.medium)).lineLimit(1).minimumScaleFactor(0.8)
                                Spacer(minLength: 6)
                                ForEach(Array(columns(row).enumerated()), id: \.offset) { _, value in
                                    Text(value).font(.subheadline.monospacedDigit()).frame(width: 52, alignment: .trailing)
                                }
                            }.listRowBackground(Color.white)
                        }
                    } header: { Text(group.name) }
                }
                if let updated = standings?.updated, !updated.isEmpty {
                    Section { EmptyView() } footer: { Text("Updated \(updated) · NCAA.com via ncaa-api") }
                }
            }
            .scrollContentBackground(.hidden).background(Palette.paper).foregroundStyle(Palette.ink)
            .overlay {
                if loading && standings == nil { ProgressView("Loading standings…") }
                else if let error, standings == nil { ContentUnavailableView("Couldn't load standings", systemImage: "wifi.exclamationmark", description: Text(error)) }
                else if !loading && standings?.conferences.isEmpty == true { ContentUnavailableView("No standings yet", systemImage: "list.number", description: Text("NCAA.com hasn't posted standings for this division.")) }
            }
            .navigationTitle("Standings")
            .task(id: division) { conference = ""; await load() }
            .refreshable { await load() }
        }
    }
    /// Conference and overall records when NCAA.com labels them, otherwise its first two columns.
    private func columns(_ row: Standings.Row) -> [String] {
        if row.conference != nil || row.overall != nil { return [row.conference ?? "–", row.overall ?? "–"] }
        return row.stats.prefix(2).map(\.value)
    }
    private func header(_ conference: Standings.Conference) -> some View {
        let first = conference.teams.first
        let labels = first.map { $0.conference != nil || $0.overall != nil ? ["CONF", "OVR"] : $0.stats.prefix(2).map { $0.label.uppercased() } } ?? []
        return HStack(spacing: 10) {
            Text("TEAM").padding(.leading, 30)
            Spacer()
            ForEach(labels, id: \.self) { Text($0).lineLimit(1).minimumScaleFactor(0.6).frame(width: 52, alignment: .trailing) }
        }.font(.system(size: 9, weight: .semibold)).tracking(1).foregroundStyle(.secondary).listRowBackground(Color.white)
    }
    private func load() async {
        guard let api = store.api else { error = "Sideout's server address is not configured in this build."; return }
        let requested = division
        loading = true
        defer { loading = false }
        do {
            let result = try await api.standings(division: requested)
            guard requested == division else { return }
            standings = result; error = nil
        } catch {
            guard !Task.isCancelled, requested == division else { return }
            if standings?.division != requested { standings = nil }
            self.error = error.localizedDescription
        }
    }
}
