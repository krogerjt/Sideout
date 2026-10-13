import SwiftUI

/// Every team on this season's scoreboards, for finding and starring favorites without waiting for a match day.
struct TeamsView: View {
    @EnvironmentObject private var store: ScoreStore
    @State private var division = "d1"
    @State private var search = ""
    @State private var teams: [TeamEntry] = []
    @State private var complete = true
    @State private var loading = false
    @State private var error: String?

    private var visible: [TeamEntry] {
        search.isEmpty ? teams : teams.filter { "\($0.name) \($0.abbreviation)".localizedCaseInsensitiveContains(search) }
    }
    var body: some View {
        NavigationStack {
            List {
                if search.isEmpty && !store.favoriteTeams.isEmpty {
                    Section("Your favorites") {
                        ForEach(store.favoriteTeams) { team in
                            row(team, abbreviation: teams.first { $0.slug == team.slug }?.abbreviation ?? team.name, showDivision: true)
                        }
                    }
                }
                Section {
                    Picker("Division", selection: $division) {
                        Text("Division I").tag("d1"); Text("Division II").tag("d2"); Text("Division III").tag("d3")
                    }.pickerStyle(.segmented).listRowBackground(Color.clear).listRowInsets(EdgeInsets())
                }
                Section {
                    ForEach(visible) { team in
                        row(FavoriteTeam(slug: team.slug, name: team.name, division: team.division), abbreviation: team.abbreviation)
                    }
                } footer: {
                    if !complete { Text("Sideout is still reading this season's schedule, so a few teams may be missing for now.") }
                    else if !teams.isEmpty { Text("Starred teams' matches go on your Lock Screen 30 minutes before first serve.") }
                }
            }
            .scrollContentBackground(.hidden).background(Palette.paper).foregroundStyle(Palette.ink)
            .overlay {
                if loading && teams.isEmpty { ProgressView("Finding teams…") }
                else if let error, teams.isEmpty { ContentUnavailableView("Couldn't load teams", systemImage: "wifi.exclamationmark", description: Text(error)) }
                else if !search.isEmpty && visible.isEmpty { ContentUnavailableView.search(text: search) }
                else if !loading && teams.isEmpty { ContentUnavailableView("No teams yet", systemImage: "volleyball", description: Text("Teams appear once this season's schedule has been read. Check back shortly.")) }
            }
            .searchable(text: $search, prompt: "Find a team")
            .navigationTitle("Teams")
            .task(id: division) { await load() }
            .refreshable { await load() }
        }
    }
    private func row(_ team: FavoriteTeam, abbreviation: String, showDivision: Bool = false) -> some View {
        HStack(spacing: 12) {
            TeamBadge(abbreviation: abbreviation, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(team.name).font(.headline)
                if showDivision {
                    Text(divisionName(team.division)).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            FavoriteStar(team: team)
        }
        .listRowBackground(Color.white)
    }
    private func divisionName(_ division: String) -> String {
        ["d1": "Division I", "d2": "Division II", "d3": "Division III"][division] ?? division.uppercased()
    }
    private func load() async {
        guard let api = store.api else { error = "Sideout's server address is not configured in this build."; return }
        let requested = division
        loading = true
        defer { loading = false }
        do {
            let list = try await api.teams(division: requested)
            guard requested == division else { return }
            teams = list.teams; complete = list.complete; error = nil
        } catch {
            guard !Task.isCancelled, requested == division else { return }
            if teams.first?.division != requested { teams = [] }
            self.error = error.localizedDescription
        }
    }
}
