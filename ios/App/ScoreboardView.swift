import SwiftUI
import ActivityKit

struct ScoreboardView: View {
    @EnvironmentObject private var store: ScoreStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var search = ""
    @State private var filter = "All matches"
    @State private var settings = false
    @State private var selectedMatch: Match?
    private let filters = ["All matches", "Live", "Favorites"]

    private var visible: [Match] {
        store.matches.filter { match in
            (filter != "Live" || match.isLive) && (filter != "Favorites" || store.isFavorite(match)) &&
            (search.isEmpty || "\(match.home.name) \(match.away.name)".localizedCaseInsensitiveContains(search))
        }.sorted { a, b in
            let order = ["live": 0, "scheduled": 1, "final": 2]
            if order[a.status, default: 3] != order[b.status, default: 3] { return order[a.status, default: 3] < order[b.status, default: 3] }
            return (a.startTime ?? 0, a.id) < (b.startTime ?? 0, b.id)
        }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    if !store.activities.isEmpty { following }
                    controls
                    if let error = store.error { message(error, icon: "wifi.exclamationmark") }
                    if store.loading && store.matches.isEmpty { ProgressView("Checking the court…").frame(maxWidth: .infinity).padding(40) }
                    else if visible.isEmpty {
                        ContentUnavailableView(search.isEmpty ? "No matches here" : "No matching teams", systemImage: "volleyball",
                            description: Text(filter == "Favorites" ? "Tap a star on a match to save a team. Its matches will appear on your Lock Screen automatically." : "Try another date or filter. Schedules use Eastern Time."))
                    } else {
                        LazyVStack(spacing: 12) {
                            ForEach(visible) { match in
                                MatchCard(match: match, open: { selectedMatch = match })
                            }
                        }
                    }
                    if !store.matches.contains(where: \.isLive) { demo }
                    Text("NCAA women’s volleyball · Scores via ncaa-api\nUpdate timing and coverage vary by match.")
                        .font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 10)
                }.padding(20)
            }
            .background(Palette.paper).foregroundStyle(Palette.ink)
            .toolbar(.hidden, for: .navigationBar)
            .refreshable { await store.refresh() }
            .sheet(isPresented: $settings) { SettingsView() }
            .sheet(item: $selectedMatch) { MatchDetailView(initial: $0) }
            .task(id: "\(store.queryKey)/\(scenePhase)") {
                guard scenePhase == .active else { return }
                while !Task.isCancelled {
                    await store.refresh()
                    do { try await Task.sleep(for: .seconds(20)) } catch { return }
                }
            }
            .onChange(of: store.dateKey) { _, _ in store.clearForDateChange() }
            .onChange(of: store.division) { _, _ in store.clearForDateChange() }
            .onOpenURL { url in
                guard url.scheme == "sideout", url.host == "match", !url.lastPathComponent.isEmpty,
                      url.lastPathComponent == ScoreAPI.demoMatchID || url.lastPathComponent.allSatisfy(\.isNumber) else { return }
                if let match = store.matches.first(where: { $0.id == url.lastPathComponent }) { selectedMatch = match }
                else if let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
                        let date = items.first(where: { $0.name == "date" })?.value,
                        let division = items.first(where: { $0.name == "division" })?.value, let api = store.api {
                    Task {
                        do { selectedMatch = try await api.match(id: url.lastPathComponent, date: date, division: division) }
                        catch { store.error = error.localizedDescription }
                    }
                }
            }
        }
        .preferredColorScheme(.light)
    }
    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 5) {
                Label("COURTSIDE, ANYWHERE", systemImage: "volleyball.fill").font(.system(size: 10, weight: .bold, design: .monospaced)).tracking(1.8)
                Text("Sideout").font(.system(size: 46, weight: .bold, design: .serif)).tracking(-2)
                Text("Every set. A little closer.").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Button { settings = true } label: {
                Image(systemName: "slider.horizontal.3").font(.title3).padding(13).background(.white.opacity(0.8), in: Circle())
            }.accessibilityLabel("Settings")
        }
    }
    private var demo: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "iphone.gen3.radiowaves.left.and.right").font(.system(size: 36)).foregroundStyle(Palette.lime)
            Text("No match on right now?").font(.system(size: 24, weight: .semibold, design: .serif))
            Text("Try a simulated match and put its live scoreboard on your Lock Screen.").foregroundStyle(.white.opacity(0.8))
            Button("Try a demo match", systemImage: "arrow.right") {
                Task { if let match = await store.demoMatch() { selectedMatch = match } }
            }.buttonStyle(.borderedProminent).tint(Palette.lime).foregroundStyle(Palette.ink)
        }.padding(22).frame(maxWidth: .infinity, alignment: .leading).foregroundStyle(.white).background(Palette.green, in: RoundedRectangle(cornerRadius: 22))
    }
    private var following: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("ON YOUR LOCK SCREEN", systemImage: "lock.iphone").font(.caption.bold()).tracking(1)
            ForEach(store.activities, id: \.id) { activity in
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(activity.attributes.awayAbbreviation) · \(activity.attributes.homeAbbreviation)").bold()
                        Text(store.activityMessages[activity.attributes.matchID] ?? "Following").font(.caption)
                    }
                    Spacer()
                    Button("Stop") { Task { await store.stop(activity) } }.buttonStyle(.bordered).tint(.white)
                }
            }
        }.padding(18).foregroundStyle(.white).background(Palette.green, in: RoundedRectangle(cornerRadius: 20))
    }
    private var controls: some View {
        VStack(spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("MATCH DAY").font(.caption2.bold()).tracking(1.5)
                    DatePicker("Match date (Eastern Time)", selection: $store.selectedDate, displayedComponents: .date)
                        .labelsHidden().environment(\.timeZone, TimeZone(identifier: "America/New_York")!)
                }
                Spacer()
                Picker("Division", selection: $store.division) {
                    Text("Division I").tag("d1"); Text("Division II").tag("d2"); Text("Division III").tag("d3")
                }.pickerStyle(.menu)
            }
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Find your team", text: $search).autocorrectionDisabled()
                if !search.isEmpty { Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Clear search") }
            }.padding(13).background(.white, in: RoundedRectangle(cornerRadius: 13))
            Picker("Filter", selection: $filter) { ForEach(filters, id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
        }
    }
    private func message(_ text: String, icon: String) -> some View {
        Label(text, systemImage: icon).font(.callout).foregroundStyle(.orange).padding(16).frame(maxWidth: .infinity, alignment: .leading).background(.white, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct MatchCard: View {
    @EnvironmentObject private var store: ScoreStore
    let match: Match
    let open: () -> Void
    var body: some View {
        VStack(spacing: 14) {
            Button(action: open) {
                VStack(spacing: 14) {
                    HStack {
                        Label(match.statusText.uppercased(), systemImage: match.isLive ? "dot.radiowaves.left.and.right" : "volleyball")
                            .font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(match.isLive ? Palette.green : .secondary)
                        Spacer()
                        Text("SETS").font(.system(size: 9, weight: .semibold)).tracking(1)
                    }
                    teamRow(match.away)
                    teamRow(match.home)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            Divider()
            HStack {
                Button { store.toggleFavorite(match.away, division: match.division) } label: {
                    Image(systemName: store.favorites.contains(match.away.slug) ? "star.fill" : "star")
                }.accessibilityLabel("Favorite \(match.away.name)")
                Button { store.toggleFavorite(match.home, division: match.division) } label: {
                    Image(systemName: store.favorites.contains(match.home.slug) ? "star.fill" : "star")
                }.accessibilityLabel("Favorite \(match.home.name)")
                Spacer()
                if let activity = store.activity(for: match) {
                    Button("Stop following", systemImage: "lock.iphone") { Task { await store.stop(activity) } }
                } else if match.isLive {
                    Button(store.startingMatch == match.id ? "Connecting…" : "Follow on Lock Screen", systemImage: "lock.iphone") { Task { await store.start(match) } }
                        .disabled(store.startingMatch != nil)
                } else { Button("Match details", systemImage: "arrow.up.right", action: open) }
            }.font(.caption.weight(.semibold)).buttonStyle(.borderless)
        }.padding(18).background(.white, in: RoundedRectangle(cornerRadius: 20))
    }
    private func teamRow(_ team: VolleyballTeam) -> some View {
        HStack(spacing: 12) {
            Text(String(team.abbreviation.prefix(3))).font(.system(size: 10, weight: .heavy, design: .rounded))
                .frame(width: 38, height: 38).background(Palette.paper, in: RoundedRectangle(cornerRadius: 11))
            if let rank = team.rank, rank > 0 { Text("\(rank)").font(.caption).foregroundStyle(.secondary) }
            Text(team.name).font(.headline).multilineTextAlignment(.leading)
            Spacer(minLength: 4)
            Text(team.setsWon.scoreText).font(.system(size: 28, weight: .semibold, design: .rounded)).monospacedDigit()
        }
    }
}
