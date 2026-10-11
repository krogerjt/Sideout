import Foundation
import Security

struct APIError: LocalizedError {
    let message: String
    var status: Int? = nil
    var errorDescription: String? { message }
}

struct ServerConfig: Decodable { let pushEnabled: Bool }
struct MatchEnvelope: Decodable { let matches: [Match] }
private struct ErrorEnvelope: Decodable { let error: String }
private struct DeviceRegistration: Decodable { let deviceId: String; let secret: String }

struct ScoreAPI {
    static let demoMatchID = "demo"
    let baseURL: URL

    /// The hosted server configured at build time through the SIDEOUT_SERVER_URL build setting.
    static let bundled: ScoreAPI? = (Bundle.main.object(forInfoDictionaryKey: "SideoutServerURL") as? String).flatMap { try? ScoreAPI(address: $0) }

    init(address: String) throws {
        guard let url = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)),
              let host = url.host, url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.scheme == "https" || (url.scheme == "http" && ["localhost", "127.0.0.1"].contains(host)) else {
            throw APIError(message: "Sideout's server address is not configured correctly.")
        }
        baseURL = url
    }

    var privacyPolicyURL: URL { baseURL.appendingPathComponent("privacy") }

    func request(_ path: String, method: String = "GET", query: [URLQueryItem] = [], body: Data? = nil, credential: String? = nil) async throws -> Data {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 25
        if let credential { request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization") }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let message = (try? JSONDecoder().decode(ErrorEnvelope.self, from: data).error) ?? "The score server couldn't complete this request."
            throw APIError(message: message, status: (response as? HTTPURLResponse)?.statusCode)
        }
        return data
    }
    func configuration() async throws -> ServerConfig {
        try JSONDecoder().decode(ServerConfig.self, from: await request("v1/config"))
    }
    func matches(date: String, division: String) async throws -> [Match] {
        try JSONDecoder().decode(MatchEnvelope.self, from: await request("v1/matches", query: [.init(name: "date", value: date), .init(name: "division", value: division)])).matches
    }
    func match(_ match: Match) async throws -> Match {
        try await self.match(id: match.id, date: match.date, division: match.division)
    }
    func match(id: String, date: String, division: String) async throws -> Match {
        try JSONDecoder().decode(Match.self, from: await request("v1/matches/\(id)", query: [.init(name: "date", value: date), .init(name: "division", value: division)]))
    }
    func register(_ attributes: VolleyballActivityAttributes, token: Data) async throws {
        let body = try JSONSerialization.data(withJSONObject: [
            "matchId": attributes.matchID, "date": attributes.date, "division": attributes.division,
            "token": token.map { String(format: "%02x", $0) }.joined()
        ])
        _ = try await authorized { try await request("v1/activities/\(attributes.subscriptionID)", method: "PUT", body: body, credential: $0) }
    }
    func unregister(_ id: String) async throws {
        do {
            _ = try await authorized { try await request("v1/activities/\(id)", method: "DELETE", credential: $0) }
        } catch let error as APIError where error.status == 404 {
            // The server already forgot this activity, so there is nothing left to stop.
        }
    }

    /// Runs a request with this install's credential, creating one on first use and once more if the server no longer knows it.
    private func authorized(_ send: (String) async throws -> Data) async throws -> Data {
        do { return try await send(try await credential()) }
        catch let error as APIError where error.status == 401 {
            Keychain.delete(Keychain.deviceAccount)
            return try await send(try await credential())
        }
    }
    private func credential() async throws -> String {
        let saved = Keychain.read(Keychain.deviceAccount)
        if !saved.isEmpty { return saved }
        let device = try JSONDecoder().decode(DeviceRegistration.self, from: await request("v1/devices", method: "POST"))
        let value = "\(device.deviceId).\(device.secret)"
        try Keychain.save(value, account: Keychain.deviceAccount)
        return value
    }
}

enum Keychain {
    static let deviceAccount = "sideout-device-credential"
    private static func baseQuery(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "Sideout", kSecAttrAccount as String: account]
    }
    static func read(_ account: String) -> String {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return "" }
        return String(decoding: data, as: UTF8.self)
    }
    static func save(_ value: String, account: String) throws {
        let data = Data(value.utf8)
        let status = SecItemUpdate(baseQuery(account) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var insert = baseQuery(account)
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw APIError(message: "Couldn't save this iPhone's Sideout credential securely.") }
        } else if status != errSecSuccess { throw APIError(message: "Couldn't update this iPhone's Sideout credential.") }
    }
    static func delete(_ account: String) { SecItemDelete(baseQuery(account) as CFDictionary) }
}
