import SwiftUI

struct MatchDetailView: View {
    @EnvironmentObject private var store: ScoreStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let initial: Match
    @State private var updated: Match?
    @State private var error: String?
    private var match: Match { updated ?? initial }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text(match.statusText.uppercased()).font(.caption.bold()).tracking(1.5).foregroundStyle(Palette.green)
                    VStack(spacing: 0) {
                        HStack {
                            Spacer()
                            Text("SETS").font(.system(size: 9, weight: .semibold)).tracking(1).foregroundStyle(.secondary)
                        }.padding(.horizontal, 18).padding(.top, 14)
                        teamRow(match.away)
                        Divider().padding(.leading, 70)
                        teamRow(match.home)
                    }.background(.white, in: RoundedRectangle(cornerRadius: 20))
                    if !match.away.slug.isEmpty && !match.home.slug.isEmpty && !store.isFavorite(match) {
                        Label("Star a team to put its matches on your Lock Screen automatically.", systemImage: "star")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if let error { Label(error, systemImage: "wifi.exclamationmark").font(.callout).foregroundStyle(.orange) }
                    if !match.sets.isEmpty { setTable }
                    else { Text("Point-by-point scores are not available for this match yet.").font(.callout).foregroundStyle(.secondary) }
                    if let activity = store.activity(for: match) {
                        Text(store.activityMessages[match.id] ?? "Following on your lock screen").font(.callout)
                        Button("Stop following", systemImage: "lock.slash") { Task { await store.stop(activity) } }.buttonStyle(.bordered)
                    } else if match.isLive {
                        Button("Follow on Lock Screen", systemImage: "lock.iphone") { Task { await store.start(match) } }
                            .buttonStyle(.borderedProminent).controlSize(.large).disabled(store.startingMatch != nil)
                    }
                    if let error = store.error { Text(error).font(.callout).foregroundStyle(.orange) }
                    if !match.venue.isEmpty { Label(match.venue, systemImage: "mappin.and.ellipse").font(.callout) }
                    TimelineView(.periodic(from: .now, by: 15)) { context in
                        VStack(alignment: .leading, spacing: 6) {
                            if context.date.timeIntervalSince1970 - match.checkedAt > 120 && match.isLive { Text("Scores may be out of date").foregroundStyle(.orange) }
                            Text("Feed checked \(Date(timeIntervalSince1970: match.checkedAt), style: .relative) ago").foregroundStyle(.secondary)
                        }.font(.caption)
                    }
                    if let url = URL(string: match.sourceURL) { Link("View on NCAA.com ↗", destination: url).font(.callout) }
                }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
            }.background(Palette.paper).foregroundStyle(Palette.ink)
                .navigationTitle("Match center").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    while !Task.isCancelled {
                        if let api = store.api {
                            do {
                                let fresh = try await api.match(initial)
                                updated = fresh; error = nil
                                await store.refreshActivity(with: fresh)
                            }
                            catch { if !Task.isCancelled { self.error = error.localizedDescription } }
                        }
                        do { try await Task.sleep(for: .seconds(20)) } catch { return }
                    }
                }
        }
    }
    private func teamRow(_ team: VolleyballTeam) -> some View {
        HStack(spacing: 6) {
            TeamBadge(abbreviation: team.abbreviation, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(team.name).font(.system(size: 21, weight: .semibold, design: .serif)).lineLimit(2).minimumScaleFactor(0.8)
                if let rank = team.rank, rank > 0 { Text("No. \(rank)").font(.caption).foregroundStyle(.secondary) }
            }.padding(.leading, 6)
            // The demo match's made-up teams have no slug, so they can't be saved.
            if !team.slug.isEmpty { FavoriteStar(team: FavoriteTeam(slug: team.slug, name: team.name, division: match.division)) }
            Spacer(minLength: 4)
            Text(team.setsWon.scoreText).font(.system(size: 40, weight: .semibold, design: .rounded)).monospacedDigit()
        }.padding(.horizontal, 18).padding(.vertical, 12)
    }
    private var setTable: some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 15) {
            GridRow {
                Text("SET").font(.caption)
                ForEach(match.sets) { Text("\($0.number)").font(.caption) }
            }.foregroundStyle(.secondary)
            GridRow {
                Text(match.away.abbreviation).font(.caption.bold())
                ForEach(match.sets) { Text($0.away.scoreText).monospacedDigit().bold() }
            }
            GridRow {
                Text(match.home.abbreviation).font(.caption.bold())
                ForEach(match.sets) { Text($0.home.scoreText).monospacedDigit().bold() }
            }
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading).background(.white, in: RoundedRectangle(cornerRadius: 18))
    }
}
