import AppIntents
import Foundation

/// Lists all chat sessions — useful for automation scripts that need a session ID.
struct ListSessionsIntent: AppIntent {
    static var title: LocalizedStringResource = "List Sessions"
    static var description = IntentDescription("Lists all Nanodot chat sessions with their titles and IDs.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let sessions = await NanoMuseSync.shared.visible(await ChatStore.shared.listSessions()) // nanoMuse: C12 — another account's chats stay hidden

        if sessions.isEmpty {
            return .result(value: "No sessions found.")
        }

        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated

        let lines = sessions.map { session -> String in
            let title = session.title ?? "Untitled"
            let age = formatter.localizedString(for: session.updatedAt, relativeTo: Date())
            return "\(title) (\(age)) — \(session.id)"
        }

        return .result(value: lines.joined(separator: "\n"))
    }
}
