import SwiftUI

@main
struct SideoutApp: App {
    @UIApplicationDelegateAdaptor private var delegate: AppDelegate
    var body: some Scene {
        WindowGroup { ScoreboardView().environmentObject(delegate.store).tint(Palette.green) }
    }
}

/// Creates the store at launch, including when iOS launches Sideout in the background for a server-started Live Activity
/// and no window is shown, so that activity's update token still reaches the server.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    lazy var store = ScoreStore()
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        _ = store
        return true
    }
}

enum Palette {
    static let paper = Color(red: 0.96, green: 0.95, blue: 0.91)
    static let ink = Color(red: 0.12, green: 0.20, blue: 0.16)
    static let green = Color(red: 0.19, green: 0.36, blue: 0.26)
    static let lime = Color(red: 0.82, green: 0.94, blue: 0.45)
}
