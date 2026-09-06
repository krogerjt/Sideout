import Foundation
import ActivityKit

struct VolleyballTeam: Codable, Hashable {
    let name: String
    let abbreviation: String
    let slug: String
    let setsWon: Int?
    let rank: Int?
    let color: String
}

struct SetScore: Codable, Hashable, Identifiable {
    let number: Int
    let home: Int?
    let away: Int?
    var id: Int { number }
}

struct Match: Codable, Hashable, Identifiable {
    let id: String
    let date: String
    let division: String
    let away: VolleyballTeam
    let home: VolleyballTeam
    let status: String
    let statusText: String
    let startTime: Double?
    let sets: [SetScore]
    let venue: String
    let checkedAt: Double
    let detailsAvailable: Bool
    let sourceURL: String

    var isLive: Bool { status == "live" }
    var currentSet: SetScore? {
        guard isLive, let digit = statusText.first(where: { "12345".contains($0) }), let number = Int(String(digit)) else { return nil }
        return sets.first { $0.number == number }
    }
    var contentState: VolleyballActivityAttributes.ContentState {
        .init(homeSets: home.setsWon, awaySets: away.setsWon, homePoints: currentSet?.home,
              awayPoints: currentSet?.away, status: status, statusText: statusText, checkedAt: checkedAt)
    }
}

struct VolleyballActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        let homeSets: Int?
        let awaySets: Int?
        let homePoints: Int?
        let awayPoints: Int?
        let status: String
        let statusText: String
        // Unix seconds, deliberately a Double instead of Swift Date's reference-date encoding.
        let checkedAt: Double
    }
    let subscriptionID: String
    let matchID: String
    let date: String
    let division: String
    let homeName: String
    let awayName: String
    let homeAbbreviation: String
    let awayAbbreviation: String
    var deepLink: URL? {
        var url = URLComponents()
        url.scheme = "sideout"; url.host = "match"; url.path = "/\(matchID)"
        url.queryItems = [.init(name: "date", value: date), .init(name: "division", value: division)]
        return url.url
    }
}

extension Optional where Wrapped == Int {
    var scoreText: String { map(String.init) ?? "–" }
}
