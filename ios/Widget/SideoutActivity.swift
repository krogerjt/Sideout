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
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(context.attributes.awayAbbreviation).font(.caption.bold())
                        Text(context.state.awayPoints.scoreText).font(.title).monospacedDigit()
                    }.padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(context.attributes.homeAbbreviation).font(.caption.bold())
                        Text(context.state.homePoints.scoreText).font(.title).monospacedDigit()
                    }.padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 5) {
                        Image(systemName: "volleyball.fill").foregroundStyle(.green)
                        Text("\(context.state.awaySets.scoreText) – \(context.state.homeSets.scoreText) sets").font(.caption)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.isStale ? "Scores may be out of date" : context.state.statusText)
                        .font(.caption).foregroundStyle(context.isStale ? .orange : .secondary)
                }
            } compactLeading: {
                HStack(spacing: 3) { Image(systemName: "volleyball.fill").foregroundStyle(.green); Text(context.attributes.awayAbbreviation).font(.system(size: 9, weight: .bold)).lineLimit(1) }
            } compactTrailing: {
                Text("\(context.state.awaySets.scoreText)–\(context.state.homeSets.scoreText)").monospacedDigit().foregroundStyle(context.isStale ? .orange : .white)
            } minimal: {
                Text("\(context.state.awaySets.scoreText)·\(context.state.homeSets.scoreText)").font(.caption2.bold()).foregroundStyle(context.isStale ? .orange : .green)
            }
            .widgetURL(context.attributes.deepLink)
            .keylineTint(.green)
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
