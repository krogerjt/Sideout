import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: ScoreStore
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @State private var key = ""
    @State private var saving = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://your-sideout-server.example", text: $address).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Server access key", text: $key).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button(saving ? "Connecting…" : "Save & connect") {
                        saving = true; error = nil
                        Task {
                            do { try await store.saveSettings(address: address, key: key); dismiss() }
                            catch { self.error = error.localizedDescription }
                            saving = false
                        }
                    }.disabled(saving || !store.activities.isEmpty)
                    if let error { Text(error).foregroundStyle(.red) }
                } header: { Text("Score server") } footer: {
                    Text("Your Sideout server checks scores and sends lock-screen updates. The access key is stored in your iPhone’s Keychain. Stop followed matches before changing servers.")
                }
                Section("Live Activities") {
                    Label(store.pushEnabled ? "Server push is configured" : "Server push is not connected", systemImage: store.pushEnabled ? "checkmark.circle" : "info.circle")
                    Text("Open a live match and tap Follow on Lock Screen. Keep Sideout open until it confirms that updates are connected.")
                    Text("Updates depend on the score feed, network access, and iOS delivery. Sideout marks the scoreboard as stale if updates stop. Live Activities last up to eight hours.").font(.footnote).foregroundStyle(.secondary)
                    if let url = URL(string: UIApplication.openSettingsURLString) { Link("Open iPhone settings", destination: url) }
                }
                Section("About") {
                    Text("Sideout · NCAA women’s volleyball")
                    Text("Independent app. Not affiliated with the NCAA. Scores are supplied through the community ncaa-api service; availability varies.").font(.footnote).foregroundStyle(.secondary)
                }
            }.navigationTitle("Settings").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
                .onAppear { address = store.serverAddress; key = Keychain.read() }
        }
    }
}
