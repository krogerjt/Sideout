import XCTest
@testable import Sideout

final class ScoreModelTests: XCTestCase {
    func testAPNsContentStateDecodesUnixTimestampAndNullScores() throws {
        let data = Data(#"{"homeSets":2,"awaySets":2,"homePoints":0,"awayPoints":null,"status":"live","statusText":"5TH SET","checkedAt":1788648300}"#.utf8)
        let state = try JSONDecoder().decode(VolleyballActivityAttributes.ContentState.self, from: data)
        XCTAssertEqual(state.homePoints, 0)
        XCTAssertNil(state.awayPoints)
        XCTAssertEqual(state.checkedAt, 1788648300)
        XCTAssertEqual(state.homePoints.scoreText, "0")
        XCTAssertEqual(state.awayPoints.scoreText, "–")
    }
    func testAPIRejectsInsecureRemoteURLAndCredentialsInURL() {
        XCTAssertThrowsError(try ScoreAPI(address: "http://example.com"))
        XCTAssertThrowsError(try ScoreAPI(address: "https://user:password@example.com"))
        XCTAssertNoThrow(try ScoreAPI(address: "http://localhost:8787"))
        XCTAssertNoThrow(try ScoreAPI(address: "https://example.com"))
    }
    func testPrivacyPolicyIsServedByTheHostedServer() throws {
        XCTAssertEqual(try ScoreAPI(address: "https://example.com").privacyPolicyURL.absoluteString, "https://example.com/privacy")
    }
    func testDeepLinkPreservesMatchDateAndDivision() {
        let attributes = VolleyballActivityAttributes(subscriptionID: "test", matchID: "6625873", date: "2026-09-05",
            division: "d1", homeName: "Boise St.", awayName: "Idaho St.", homeAbbreviation: "BOISE", awayAbbreviation: "ID ST")
        let parts = URLComponents(url: attributes.deepLink!, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(parts.path, "/6625873")
        XCTAssertEqual(parts.queryItems?.first(where: { $0.name == "date" })?.value, "2026-09-05")
        XCTAssertEqual(parts.queryItems?.first(where: { $0.name == "division" })?.value, "d1")
    }
    @MainActor func testFavoritesPersistInCoreDataAndImportLegacySlugs() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "SideoutTests-\(UUID().uuidString)"))
        defaults.set(["boise-st"], forKey: "favorites")
        let store = FavoritesStore(inMemory: true)
        store.importLegacy(defaults)
        XCTAssertNil(defaults.stringArray(forKey: "favorites"))
        XCTAssertEqual(store.all(), [FavoriteTeam(slug: "boise-st", name: "boise-st", division: "d1")])
        let boise = VolleyballTeam(name: "Boise St.", abbreviation: "BOISE", slug: "boise-st", setsWon: nil, rank: nil, color: "#000000")
        XCTAssertTrue(store.resolve([(boise, "d1")]))
        XCTAssertFalse(store.resolve([(boise, "d2")]), "a resolved team keeps its name and division")
        store.save(FavoriteTeam(slug: "idaho-st", name: "Idaho St.", division: "d1"))
        store.save(FavoriteTeam(slug: "idaho-st", name: "Idaho St.", division: "d1"))
        XCTAssertEqual(store.all().map(\.name), ["Boise St.", "Idaho St."])
        store.remove("boise-st")
        XCTAssertEqual(store.all().map(\.slug), ["idaho-st"])
    }
}
