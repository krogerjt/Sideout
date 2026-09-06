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
        XCTAssertThrowsError(try ScoreAPI(address: "http://example.com", key: "test"))
        XCTAssertThrowsError(try ScoreAPI(address: "https://user:password@example.com", key: "test"))
        XCTAssertNoThrow(try ScoreAPI(address: "http://localhost:8787", key: "test"))
        XCTAssertNoThrow(try ScoreAPI(address: "https://example.com", key: "test"))
    }
    func testDeepLinkPreservesMatchDateAndDivision() {
        let attributes = VolleyballActivityAttributes(subscriptionID: "test", matchID: "6625873", date: "2026-09-05",
            division: "d1", homeName: "Boise St.", awayName: "Idaho St.", homeAbbreviation: "BOISE", awayAbbreviation: "ID ST")
        let parts = URLComponents(url: attributes.deepLink!, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(parts.path, "/6625873")
        XCTAssertEqual(parts.queryItems?.first(where: { $0.name == "date" })?.value, "2026-09-05")
        XCTAssertEqual(parts.queryItems?.first(where: { $0.name == "division" })?.value, "d1")
    }
}
