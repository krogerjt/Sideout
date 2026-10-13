import SwiftUI

@main
struct SideoutApp: App {
    @UIApplicationDelegateAdaptor private var delegate: AppDelegate
    var body: some Scene {
        WindowGroup { RootView().environmentObject(delegate.store).tint(Palette.green) }
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
    static let star = Color(red: 0.93, green: 0.66, blue: 0.10)
}

struct RootView: View {
    @EnvironmentObject private var store: ScoreStore
    @State private var tab = Screen.scores
    enum Screen { case scores, standings, teams }
    var body: some View {
        TabView(selection: $tab) {
            ScoreboardView().tabItem { Label("Scores", systemImage: "volleyball") }.tag(Screen.scores)
            StandingsView().tabItem { Label("Standings", systemImage: "list.number") }.tag(Screen.standings)
            TeamsView().tabItem { Label("Teams", systemImage: "star") }.tag(Screen.teams)
        }
        .onOpenURL { url in
            tab = .scores
            store.openedURL = url
        }
        .preferredColorScheme(.light)
    }
}

/// A team's short-name badge, shared by the scoreboard, match center, and team list.
struct TeamBadge: View {
    let abbreviation: String
    var size: CGFloat = 38
    var body: some View {
        Text(String(abbreviation.prefix(3))).font(.system(size: size * 0.27, weight: .heavy, design: .rounded))
            .frame(width: size, height: size).background(Palette.paper, in: RoundedRectangle(cornerRadius: size * 0.29))
    }
}

/// The star that saves a team as a favorite, shown right beside the team's name.
struct FavoriteStar: View {
    @EnvironmentObject private var store: ScoreStore
    let team: FavoriteTeam
    var body: some View {
        let saved = store.favorites.contains(team.slug)
        Button { store.toggleFavorite(team) } label: {
            Image(systemName: saved ? "star.fill" : "star").font(.title3).foregroundStyle(saved ? Palette.star : Color.secondary)
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .sensoryFeedback(.selection, trigger: saved)
        .accessibilityLabel(saved ? "Remove \(team.name) from favorites" : "Add \(team.name) to favorites")
    }
}
