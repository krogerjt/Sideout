import Foundation
import Security

struct APIError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

struct ServerConfig: Decodable { let pushEnabled: Bool }
struct MatchEnvelope: Decodable { let matches: [Match] }
private struct ErrorEnvelope: Decodable { let error: String }

struct ScoreAPI {
    let baseURL: URL
    let key: String

    init(address: String, key: String) throws {
        guard let url = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)),
              let host = url.host, url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.scheme == "https" || (url.scheme == "http" && ["localhost", "127.0.0.1"].contains(host)) else {
            throw APIError(message: "Enter your server's HTTPS URL. The simulator also supports http://localhost:8787.")
        }
        guard !key.isEmpty else { throw APIError(message: "Enter your server access key.") }
        baseURL = url; self.key = key
    }

    func request(_ path: String, method: String = "GET", query: [URLQueryItem] = [], body: Data? = nil) async throws -> Data {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 25
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let message = (try? JSONDecoder().decode(ErrorEnvelope.self, from: data).error) ?? "The score server couldn't complete this request."
            throw APIError(message: message)
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
        _ = try await request("v1/activities/\(attributes.subscriptionID)", method: "PUT", body: body)
    }
    func unregister(_ id: String) async throws {
        _ = try await request("v1/activities/\(id)", method: "DELETE")
    }
}

enum Keychain {
    private static let account = "sideout-server-access-key"
    static func read() -> String {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "Sideout", kSecAttrAccount as String: account,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return "" }
        return String(decoding: data, as: UTF8.self)
    }
    static func save(_ value: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "Sideout", kSecAttrAccount as String: account]
        let data = Data(value.utf8)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw APIError(message: "Couldn't save the access key securely.") }
        } else if status != errSecSuccess { throw APIError(message: "Couldn't update the saved access key.") }
    }
}
