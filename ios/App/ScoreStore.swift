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
    @Published var favorites = Set(UserDefaults.standard.stringArray(forKey: "favorites") ?? [])
    @Published var activities: [Activity<VolleyballActivityAttributes>] = []
    @Published var activityMessages: [String: String] = [:]
    @Published var startingMatch: String?
    private var tokenTasks: [String: Task<Void, Never>] = [:]
    private var stateTasks: [String: Task<Void, Never>] = [:]
    private var registrationTasks: [String: Task<Void, Never>] = [:]
    private var pendingDeletes: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "pendingDeletes") ?? [])
    private var generation = 0

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
    func toggleFavorite(_ team: VolleyballTeam) {
        guard !team.slug.isEmpty else { return }
        if favorites.contains(team.slug) { favorites.remove(team.slug) } else { favorites.insert(team.slug) }
        UserDefaults.standard.set(Array(favorites), forKey: "favorites")
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
