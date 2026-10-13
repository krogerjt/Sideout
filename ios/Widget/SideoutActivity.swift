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
            let score = IslandScore(context: context)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    score.expandedSide(away: true).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    score.expandedSide(away: false).padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(score.setLabel).font(.system(size: 11, weight: .heavy)).tracking(1).foregroundStyle(score.accent)
                        .lineLimit(1).minimumScaleFactor(0.7)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 6) {
                        Image(systemName: "volleyball.fill").font(.caption2).foregroundStyle(score.accent)
                        Text("SETS").font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.55))
                        Text("\(context.state.awaySets.scoreText) – \(context.state.homeSets.scoreText)")
                            .font(.system(.subheadline, design: .rounded, weight: .bold)).monospacedDigit()
                        Spacer(minLength: 6)
                        Text(context.isStale ? "Update delayed" : "\(context.attributes.awayName) at \(context.attributes.homeName)")
                            .font(.caption2).foregroundStyle(context.isStale ? .orange : .white.opacity(0.55)).lineLimit(1)
                    }.padding(.horizontal, 6).padding(.top, 2)
                }
            } compactLeading: {
                score.compactSide(away: true)
            } compactTrailing: {
                score.compactSide(away: false)
            } minimal: {
                Text(score.minimalText).font(.system(size: 12, weight: .bold, design: .rounded)).monospacedDigit()
                    .minimumScaleFactor(0.6).foregroundStyle(context.isStale ? .orange : score.accent)
            }
            .widgetURL(context.attributes.deepLink)
            .keylineTint(score.accent)
        }
    }
}

/// The Dynamic Island's pieces: each team's current-set points with its sets won as pips, away team on the left.
/// After a match ends there are no set points, so the sets won take the points' place.
struct IslandScore {
    let context: ActivityViewContext<VolleyballActivityAttributes>
    let accent = Color(red: 0.82, green: 0.94, blue: 0.45)
    private var state: VolleyballActivityAttributes.ContentState { context.state }
    private var showsPoints: Bool { state.homePoints != nil || state.awayPoints != nil }

    func abbreviation(away: Bool) -> String {
        String((away ? context.attributes.awayAbbreviation : context.attributes.homeAbbreviation).prefix(3)).uppercased()
    }
    func big(away: Bool) -> Int? {
        showsPoints ? (away ? state.awayPoints : state.homePoints) : (away ? state.awaySets : state.homeSets)
    }
    func sets(away: Bool) -> Int { (away ? state.awaySets : state.homeSets) ?? 0 }
    /// The side ahead in the current set (or the match, once it is over) is drawn in the accent color.
    func leads(away: Bool) -> Bool {
        guard let mine = big(away: away), let theirs = big(away: !away) else { return false }
        return mine > theirs
    }
    var setLabel: String {
        if context.isStale { return "UPDATE DELAYED" }
        return state.statusText.uppercased()
    }
    var minimalText: String { "\(big(away: true).scoreText)-\(big(away: false).scoreText)" }

    @ViewBuilder func pips(away: Bool, size: CGFloat) -> some View {
        HStack(spacing: size * 0.6) {
            ForEach(0..<3, id: \.self) { index in
                Circle().fill(index < sets(away: away) ? accent : Color.white.opacity(0.25)).frame(width: size, height: size)
            }
        }
        .accessibilityElement().accessibilityLabel("\(sets(away: away)) sets")
    }
    @ViewBuilder func compactSide(away: Bool) -> some View {
        let label = VStack(alignment: away ? .leading : .trailing, spacing: 2) {
            Text(abbreviation(away: away)).font(.system(size: 9, weight: .heavy)).foregroundStyle(.white.opacity(0.8))
            pips(away: away, size: 4)
        }
        let points = Text(big(away: away).scoreText).font(.system(size: 16, weight: .bold, design: .rounded)).monospacedDigit()
            .foregroundStyle(context.isStale ? .orange : leads(away: away) ? accent : .white)
        HStack(spacing: 4) {
            if away {
                label
                points
            } else {
                points
                label
            }
        }
    }
    @ViewBuilder func expandedSide(away: Bool) -> some View {
        let label = VStack(alignment: away ? .leading : .trailing, spacing: 4) {
            Text(abbreviation(away: away)).font(.system(size: 13, weight: .heavy)).foregroundStyle(.white.opacity(0.85))
            pips(away: away, size: 6)
        }
        let points = Text(big(away: away).scoreText).font(.system(size: 34, weight: .bold, design: .rounded)).monospacedDigit()
            .foregroundStyle(leads(away: away) ? accent : .white).minimumScaleFactor(0.7)
        HStack(spacing: 8) {
            if away {
                label
                points
            } else {
                points
                label
            }
        }
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
