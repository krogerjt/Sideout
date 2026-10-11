import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: ScoreStore
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section("Live Activities") {
                    Label(store.pushEnabled ? "Lock-screen updates are available" : "Lock-screen updates are temporarily unavailable",
                          systemImage: store.pushEnabled ? "checkmark.circle" : "info.circle")
                    Text("Open a live match and tap Follow on Lock Screen. Keep Sideout open until it confirms that updates are connected.")
                    Text("Updates depend on the score feed, network access, and iOS delivery. Sideout marks the scoreboard as stale if updates stop. Live Activities last up to eight hours.").font(.footnote).foregroundStyle(.secondary)
                    if let url = URL(string: UIApplication.openSettingsURLString) { Link("Open iPhone settings", destination: url) }
                }
                Section("About") {
                    Text("Sideout · NCAA women’s volleyball")
                    Text("Independent app. Not affiliated with the NCAA. Scores are supplied through the community ncaa-api service; availability varies.").font(.footnote).foregroundStyle(.secondary)
                    if let api = store.api { Link("Privacy policy", destination: api.privacyPolicyURL) }
                }
            }.navigationTitle("Settings").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
