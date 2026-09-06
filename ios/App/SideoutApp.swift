import SwiftUI

@main
struct SideoutApp: App {
    @StateObject private var store = ScoreStore()
    var body: some Scene {
        WindowGroup { ScoreboardView().environmentObject(store).tint(Palette.green) }
    }
}

enum Palette {
    static let paper = Color(red: 0.96, green: 0.95, blue: 0.91)
    static let ink = Color(red: 0.12, green: 0.20, blue: 0.16)
    static let green = Color(red: 0.19, green: 0.36, blue: 0.26)
    static let lime = Color(red: 0.82, green: 0.94, blue: 0.45)
}
