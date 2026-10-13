import SwiftUI
import WidgetKit
import ActivityKit

@main
struct SideoutWidgetBundle: WidgetBundle {
    var body: some Widget { SideoutLiveActivity() }
}

struct SideoutLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VolleyballActivityAttributes.self) { context in
            LockScreenScore(context: context)
                .activityBackgroundTint(Color(red: 0.08, green: 0.16, blue: 0.12))
                .activitySystemActionForegroundColor(.white)
                .widgetURL(context.attributes.deepLink)
        } dynamicIsland: { context in
            // Away on the left and home on the right, matching the Lock Screen's away-then-home rows.
            // Points are the big number; the pips under each team are sets won (first to three).
            let island = IslandScore(context: context)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    island.team(island.away, sets: context.state.awaySets, alignment: .leading).padding(.leading, 8)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    island.team(island.home, sets: context.state.homeSets, alignment: .trailing).padding(.trailing, 8)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 2) {
                        HStack(spacing: 10) {
                            island.points(context.state.awayPoints, size: 34)
                            Text("–").font(.system(size: 22, weight: .semibold, design: .rounded)).foregroundStyle(.white.opacity(0.4))
                            island.points(context.state.homePoints, size: 34)
                        }
                        Text("SETS \(context.state.awaySets.scoreText)–\(context.state.homeSets.scoreText)")
                            .font(.system(size: 11, weight: .bold, design: .rounded)).tracking(0.8).monospacedDigit().foregroundStyle(.white.opacity(0.7))
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 6) {
                        Image(systemName: context.isStale ? "exclamationmark.arrow.circlepath" : "volleyball.fill")
                        Text(context.isStale ? "SCORES MAY BE OUT OF DATE" : context.state.statusText.uppercased()).lineLimit(1)
                    }.font(.system(size: 11, weight: .semibold)).tracking(0.8).foregroundStyle(context.isStale ? .orange : IslandScore.accent)
                }
            } compactLeading: {
                HStack(spacing: 5) {
                    island.label(island.away, sets: context.state.awaySets)
                    island.points(context.state.awayPoints, size: 16)
                }.padding(.leading, 2)
            } compactTrailing: {
                HStack(spacing: 5) {
                    island.points(context.state.homePoints, size: 16)
                    island.label(island.home, sets: context.state.homeSets)
                }.padding(.trailing, 2)
            } minimal: {
                Text("\(context.state.awayPoints.scoreText)-\(context.state.homePoints.scoreText)")
                    .font(.system(size: 12, weight: .bold, design: .rounded)).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
                    .foregroundStyle(context.isStale ? .orange : IslandScore.accent)
            }
            .widgetURL(context.attributes.deepLink)
            .keylineTint(IslandScore.accent)
        }
    }
}

/// Shared pieces of the Dynamic Island layouts.
struct IslandScore {
    static let accent = Color(red: 0.82, green: 0.94, blue: 0.45)
    let context: ActivityViewContext<VolleyballActivityAttributes>
    var away: String { String(context.attributes.awayAbbreviation.prefix(3)).uppercased() }
    var home: String { String(context.attributes.homeAbbreviation.prefix(3)).uppercased() }

    func points(_ value: Int?, size: CGFloat) -> some View {
        Text(value.scoreText).font(.system(size: size, weight: .bold, design: .rounded)).monospacedDigit()
            .foregroundStyle(context.isStale ? .orange : Self.accent).contentTransition(.numericText())
    }
    /// Team abbreviation over its set pips, compact enough for the island's side regions.
    func label(_ name: String, sets: Int?) -> some View {
        VStack(spacing: 2) {
            Text(name).font(.system(size: 10, weight: .heavy, design: .rounded)).foregroundStyle(.white)
            SetPips(won: sets ?? 0, size: 4)
        }
    }
    func team(_ name: String, sets: Int?, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 5) {
            Text(name).font(.system(size: 17, weight: .heavy, design: .rounded)).foregroundStyle(.white)
            SetPips(won: sets ?? 0, size: 7)
        }
    }
}

/// One dot per set needed to win the match, filled for each set won.
struct SetPips: View {
    let won: Int
    var size: CGFloat
    var body: some View {
        HStack(spacing: size * 0.6) {
            ForEach(0..<3, id: \.self) { index in
                Circle().fill(index < won ? IslandScore.accent : .white.opacity(0.25)).frame(width: size, height: size)
            }
        }.accessibilityLabel("\(won) \(won == 1 ? "set" : "sets") won")
    }
}

struct LockScreenScore: View {
    let context: ActivityViewContext<VolleyballActivityAttributes>
    private let accent = Color(red: 0.82, green: 0.94, blue: 0.45)
    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Label("SIDEOUT", systemImage: "volleyball.fill").font(.system(size: 10, weight: .heavy)).tracking(1.3).foregroundStyle(accent)
                Spacer()
                Text(context.isStale ? "UPDATE DELAYED" : context.state.statusText.uppercased())
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(context.isStale ? .orange : accent)
            }
            HStack {
                Text(context.isStale ? "SCORES MAY BE OUT OF DATE" : "NCAA WOMEN’S VOLLEYBALL").font(.system(size: 8, weight: .medium)).foregroundStyle(context.isStale ? .orange : .white.opacity(0.5))
                Spacer()
                Text("SETS").frame(width: 32)
                Text("POINTS").frame(width: 46)
            }.font(.system(size: 8, weight: .medium)).foregroundStyle(.white.opacity(0.5))
            row(context.attributes.awayName, sets: context.state.awaySets, points: context.state.awayPoints)
            row(context.attributes.homeName, sets: context.state.homeSets, points: context.state.homePoints)
        }.padding(16).foregroundStyle(.white)
    }
    private func row(_ name: String, sets: Int?, points: Int?) -> some View {
        HStack {
            Text(name).font(.system(.subheadline, design: .rounded, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            Spacer(minLength: 6)
            Text(sets.scoreText).font(.title3.bold()).monospacedDigit().frame(width: 32)
            Text(points.scoreText).font(.title2.bold()).monospacedDigit().foregroundStyle(accent).frame(width: 46)
        }
    }
}
