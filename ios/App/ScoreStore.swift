import SwiftUI
import Combine
import ActivityKit

@MainActor
final class ScoreStore: ObservableObject {
    @Published var matches: [Match] = []
    @Published var selectedDate = Date()
    @Published var division = "d1"
    @Published var error: String?
    @Published var loading = false
    @Published var pushEnabled = false
    @Published private(set) var favoriteTeams: [FavoriteTeam] = []
    @Published var autoStart = UserDefaults.standard.object(forKey: "autoStartFavorites") as? Bool ?? true {
        didSet { UserDefaults.standard.set(autoStart, forKey: "autoStartFavorites"); syncAutoStart() }
    }
    @Published private(set) var autoStartStatus: String?
    /// Unlocked by tapping the version in Settings; holds tools like the demo match that family members don't need.
    @Published var developerMode = UserDefaults.standard.bool(forKey: "developerMode") {
        didSet { UserDefaults.standard.set(developerMode, forKey: "developerMode"); if !developerMode { showDemo = false } }
    }
    @Published var showDemo = UserDefaults.standard.bool(forKey: "showDemoMatch") {
        didSet { UserDefaults.standard.set(showDemo, forKey: "showDemoMatch") }
    }
    /// A sideout:// link waiting for the scoreboard to open it, set when a link arrives while another tab is showing.
    @Published var openedURL: URL?
    @Published var activities: [Activity<VolleyballActivityAttributes>] = []
    @Published var activityMessages: [String: String] = [:]
    @Published var startingMatch: String?
    private var tokenTasks: [String: Task<Void, Never>] = [:]
    private var stateTasks: [String: Task<Void, Never>] = [:]
    private var registrationTasks: [String: Task<Void, Never>] = [:]
    private var pendingDeletes: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "pendingDeletes") ?? [])
    private var generation = 0
    private let favoriteStore = FavoritesStore()
    private var pushToStartToken: Data?
    private var autoStartSynced = false
    private var autoStartTask: Task<Void, Never>?
    private var pushToStartTask: Task<Void, Never>?
    private var activityUpdatesTask: Task<Void, Never>?

    var favorites: Set<String> { Set(favoriteTeams.map(\.slug)) }
    /// Push-to-start, which lets the server begin a Live Activity while Sideout is closed, arrived in iOS 17.2.
    var autoStartSupported: Bool { if #available(iOS 17.2, *) { true } else { false } }

    var api: ScoreAPI? { ScoreAPI.bundled }
    var dateKey: String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "America/New_York")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: selectedDate)
    }
    var queryKey: String { "\(dateKey)/\(division)" }
    init() {
        // Earlier builds connected to a self-hosted server with a shared key; that setup is gone.
        Keychain.delete("sideout-server-access-key")
        UserDefaults.standard.removeObject(forKey: "serverAddress")
        activities = Activity<VolleyballActivityAttributes>.activities.filter { $0.activityState == .active || $0.activityState == .stale }
        for activity in activities { observe(activity) }
        favoriteStore.importLegacy()
        favoriteTeams = favoriteStore.all()
        // Activities the server starts for favorite teams appear here, often while iOS has launched Sideout in the background.
        activityUpdatesTask = Task { [weak self] in
            for await activity in Activity<VolleyballActivityAttributes>.activityUpdates {
                guard let self else { return }
                guard !self.activities.contains(where: { $0.id == activity.id }),
                      activity.activityState == .active || activity.activityState == .stale else { continue }
                self.activities.append(activity)
                self.observe(activity)
            }
        }
        if #available(iOS 17.2, *) {
            pushToStartTask = Task { [weak self] in
                if let token = Activity<VolleyballActivityAttributes>.pushToStartToken { self?.pushToStartToken = token }
                self?.syncAutoStart()
                for await token in Activity<VolleyballActivityAttributes>.pushToStartTokenUpdates {
                    guard let self else { return }
                    self.pushToStartToken = token
                    self.syncAutoStart()
                }
            }
        } else { syncAutoStart() }
    }
    func refresh() async {
        guard let api else { error = "Sideout's server address is not configured in this build."; return }
        generation += 1
        let requestGeneration = generation
        let requestKey = queryKey
        loading = true
        defer { if requestGeneration == generation { loading = false } }
        await cleanPendingDeletes(api)
        do {
            let config = try await api.configuration()
            let results = try await api.matches(date: dateKey, division: division)
            guard !Task.isCancelled, requestGeneration == generation, requestKey == queryKey else { return }
            pushEnabled = config.pushEnabled
            matches = results; error = nil
            let seen = results.flatMap { [($0.home, $0.division), ($0.away, $0.division)] }.filter { favorites.contains($0.0.slug) }
            if favoriteStore.resolve(seen) { favoriteTeams = favoriteStore.all(); autoStartSynced = false }
            if !autoStartSynced { syncAutoStart() }
        } catch {
            guard !Task.isCancelled, requestGeneration == generation, requestKey == queryKey else { return }
            self.error = error.localizedDescription
        }
    }
    func clearForDateChange() { matches = []; error = nil }
    /// Loads the simulated match the server always has live, for trying Sideout when no real match is on.
    func demoMatch() async -> Match? {
        guard let api else { return nil }
        do { return try await api.match(id: ScoreAPI.demoMatchID, date: dateKey, division: "d1") }
        catch { self.error = error.localizedDescription; return nil }
    }
    func toggleFavorite(_ team: VolleyballTeam, division: String) {
        toggleFavorite(FavoriteTeam(slug: team.slug, name: team.name, division: division))
    }
    /// Saves by slug, the same key the server matches scoreboards on, whether the team came from a match or the team list.
    func toggleFavorite(_ team: FavoriteTeam) {
        guard !team.slug.isEmpty else { return }
        if favorites.contains(team.slug) { favoriteStore.remove(team.slug) }
        else { favoriteStore.save(team) }
        favoriteTeams = favoriteStore.all()
        syncAutoStart()
    }
    func removeFavorite(_ team: FavoriteTeam) {
        favoriteStore.remove(team.slug)
        favoriteTeams = favoriteStore.all()
        syncAutoStart()
    }

    /// Tells the server which favorite teams to start automatically, or that it should stop.
    func syncAutoStart() {
        autoStartTask?.cancel()
        autoStartSynced = false
        guard let api else { return }
        let teams = autoStart && autoStartSupported ? favoriteTeams : []
        let token = pushToStartToken
        autoStartTask = Task { [weak self] in
            do {
                if teams.isEmpty { try await api.unregisterAutoStart() }
                else if let token { try await api.registerAutoStart(token: token, teams: teams) }
                guard let self, !Task.isCancelled else { return }
                self.autoStartSynced = teams.isEmpty || token != nil
                self.autoStartStatus = self.describeAutoStart(teams: teams.count, registered: token != nil)
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.autoStartStatus = "Couldn't reach Sideout to set up automatic starts. It will retry when the app is open. \(error.localizedDescription)"
            }
        }
    }
    private func describeAutoStart(teams: Int, registered: Bool) -> String? {
        guard autoStart else { return nil }
        guard autoStartSupported else { return "Automatic starts need iOS 17.2 or later." }
        guard teams > 0 else { return "Star a team to start its matches automatically." }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return "Turn on Live Activities for Sideout in iPhone Settings." }
        guard registered else { return "Waiting for iOS to allow automatic starts…" }
        return "On for \(teams) \(teams == 1 ? "team" : "teams"). Scoreboards appear 30 minutes before first serve."
    }
    func isFavorite(_ match: Match) -> Bool { favorites.contains(match.home.slug) || favorites.contains(match.away.slug) }
    func activity(for match: Match) -> Activity<VolleyballActivityAttributes>? { activities.first { $0.attributes.matchID == match.id } }

    func start(_ match: Match) async {
        guard startingMatch == nil, activity(for: match) == nil, let api else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { error = "Enable Live Activities for Sideout in iPhone Settings."; return }
        startingMatch = match.id
        defer { startingMatch = nil }
        do {
            guard try await api.configuration().pushEnabled else { throw APIError(message: "Lock-screen updates are temporarily unavailable. Please try again later.") }
            let current = try await api.match(match)
            guard current.isLive else { throw APIError(message: "This match is no longer in progress.") }
            let attributes = VolleyballActivityAttributes(subscriptionID: UUID().uuidString, matchID: current.id,
                date: current.date, division: current.division, homeName: current.home.name, awayName: current.away.name,
                homeAbbreviation: current.home.abbreviation, awayAbbreviation: current.away.abbreviation)
            let activity = try Activity.request(attributes: attributes,
                content: ActivityContent(state: current.contentState, staleDate: Date(timeIntervalSince1970: current.checkedAt + 120)), pushType: .token)
            activities.append(activity)
            observe(activity)
        } catch { self.error = error.localizedDescription }
    }
    private func observe(_ activity: Activity<VolleyballActivityAttributes>) {
        let id = activity.attributes.subscriptionID
        activityMessages[activity.attributes.matchID] = "Connecting lock-screen updates…"
        if let token = activity.pushToken { register(activity, token: token) }
        tokenTasks[id] = Task { [weak self] in
            for await token in activity.pushTokenUpdates {
                guard !Task.isCancelled else { return }
                self?.register(activity, token: token)
            }
        }
        stateTasks[id] = Task { [weak self] in
            for await state in activity.activityStateUpdates {
                if state == .ended || state == .dismissed {
                    await self?.remove(activity)
                    return
                }
            }
        }
    }
    private func register(_ activity: Activity<VolleyballActivityAttributes>, token: Data) {
        let id = activity.attributes.subscriptionID
        registrationTasks[id]?.cancel()
        registrationTasks[id] = Task { [weak self] in
            let background = UIApplication.shared.beginBackgroundTask(withName: "Connect Live Activity")
            defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background) } }
            var delay: UInt64 = 2
            while !Task.isCancelled {
                guard let self, let api = self.api else { return }
                do {
                    try await api.register(activity.attributes, token: token)
                    guard !Task.isCancelled else { return }
                    self.activityMessages[activity.attributes.matchID] = "Following on your lock screen"
                    return
                } catch {
                    guard !Task.isCancelled else { return }
                    self.activityMessages[activity.attributes.matchID] = "Updates not connected: \(error.localizedDescription) Retrying while the app is open."
                    do { try await Task.sleep(nanoseconds: delay * 1_000_000_000) } catch { return }
                    delay = min(delay * 2, 60)
                }
            }
        }
    }
    /// Shows a freshly fetched score on the followed Live Activity right away while the app is open, so it never lags
    /// the match center; the server's pushes keep it current once the app is closed. Board rows lack set points, so only
    /// detailed matches are applied.
    func refreshActivity(with match: Match) async {
        guard match.detailsAvailable, let activity = activity(for: match),
              match.checkedAt > activity.content.state.checkedAt else { return }
        await activity.update(ActivityContent(state: match.contentState, staleDate: Date(timeIntervalSince1970: match.checkedAt + 120)))
    }
    func stop(_ activity: Activity<VolleyballActivityAttributes>) async {
        await activity.end(nil, dismissalPolicy: .immediate)
        await remove(activity)
    }
    private func remove(_ activity: Activity<VolleyballActivityAttributes>) async {
        let id = activity.attributes.subscriptionID
        tokenTasks.removeValue(forKey: id)?.cancel()
        registrationTasks.removeValue(forKey: id)?.cancel()
        stateTasks.removeValue(forKey: id)?.cancel()
        activities.removeAll { $0.id == activity.id }
        activityMessages.removeValue(forKey: activity.attributes.matchID)
        pendingDeletes.insert(id)
        UserDefaults.standard.set(Array(pendingDeletes), forKey: "pendingDeletes")
        if let api { await cleanPendingDeletes(api) }
    }
    private func cleanPendingDeletes(_ api: ScoreAPI) async {
        for id in Array(pendingDeletes) {
            do { try await api.unregister(id); pendingDeletes.remove(id) } catch { continue }
        }
        UserDefaults.standard.set(Array(pendingDeletes), forKey: "pendingDeletes")
    }
}
