//
//  NanoMuseCore.swift
//  Nanodot
//
//  Small shared pieces the Nanodot files lean on: where our data lives,
//  day keys, the fenced blocks the agent writes for the app
//  (```nanomuse-…```), and the per-session system-prompt addenda. Android:
//  io.github.nanomuse.chat.SessionAddenda and the helpers next to it.
//

import Foundation

// MARK: - Paths

enum NanoMuseDirs {
    /// Our own files, beside upstream's private config (goals, routines, the feed).
    /// Kept out of the Files-visible app-group folder on purpose.
    static var root: URL {
        let url = AIChatViewModel.minisConfigRoot.appendingPathComponent("nanomuse", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// The memory folder the agent sees as /var/minis/memory (SOUL.md, GLOBAL.md, the diary).
    static var memory: URL { AIChatViewModel.minisMemoryPersistentDir }
}

// MARK: - Dates

enum NanoMuseDay {
    static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func key(_ date: Date = Date()) -> String { formatter.string(from: date) }

    static func date(_ key: String) -> Date? { formatter.date(from: key) }

    static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// "HH:mm", zero-padded.
    static func clock(hour: Int, minute: Int) -> String {
        String(format: "%02d:%02d", hour, minute)
    }

    /// "08:30" → (8, 30); nil when it is not a time of day.
    static func parseClock(_ text: String?) -> (hour: Int, minute: Int)? {
        guard let text = text?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        let parts = text.split(separator: ":")
        guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]), (0...23).contains(h), (0...59).contains(m) else { return nil }
        return (h, m)
    }

    /// "just now" · a time today · "Yesterday" · a medium date.
    static func relative(_ date: Date, now: Date = Date()) -> String {
        let diff = now.timeIntervalSince(date)
        if diff < 60 { return AppLocalized("just now") }
        if Calendar.current.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if Calendar.current.isDateInYesterday(date) { return AppLocalized("Yesterday") }
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}

// MARK: - Fenced blocks

/// The ```nanomuse-<kind>``` blocks a reply may end with: JSON for the app,
/// never shown as code. Parsing is forgiving (the last block wins, a bad one
/// is ignored); stripping leaves the prose.
enum NanoMuseFences {
    static let prefix = "```nanomuse-"

    static func contains(_ text: String, kind: String? = nil) -> Bool {
        if let kind { return text.contains("```nanomuse-\(kind)") }
        return text.contains(prefix)
    }

    /// Every block of `kind`, in order, as raw text.
    static func blocks(_ kind: String, in text: String?) -> [String] {
        guard let text, text.contains("```nanomuse-\(kind)") else { return [] }
        let pattern = "```nanomuse-\(NSRegularExpression.escapedPattern(for: kind))[ \\t]*\\r?\\n([\\s\\S]*?)```"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap { match in
            guard match.numberOfRanges > 1 else { return nil }
            return ns.substring(with: match.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    /// The last block of `kind` as a JSON object, or nil.
    static func lastObject(_ kind: String, in text: String?) -> [String: Any]? {
        for raw in blocks(kind, in: text).reversed() {
            if let data = raw.data(using: .utf8),
               let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                return object
            }
        }
        return nil
    }

    /// Every block of `kind` as JSON objects, in order (bad ones skipped).
    static func objects(_ kind: String, in text: String?) -> [[String: Any]] {
        blocks(kind, in: text).compactMap { raw in
            guard let data = raw.data(using: .utf8) else { return nil }
            return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        }
    }

    /// A block as the agent writes it.
    static func fence(_ kind: String, _ object: [String: Any]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
        let json = String(data: data, encoding: .utf8) ?? "{}"
        return "```nanomuse-\(kind)\n\(json)\n```"
    }

    /// The text with every ```nanomuse-…``` block removed.
    static func strip(_ text: String) -> String {
        guard text.contains(prefix) else { return text }
        guard let regex = try? NSRegularExpression(pattern: "```nanomuse-[a-z-]+[ \\t]*\\r?\\n[\\s\\S]*?```[ \\t]*\\r?\\n?") else { return text }
        let ns = text as NSString
        return regex.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: ns.length), withTemplate: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The text split into prose and blocks, in order, for rendering.
    enum Piece: Equatable {
        case prose(String)
        case block(kind: String, json: String)
    }

    static func pieces(_ text: String) -> [Piece] {
        guard text.contains(prefix),
              let regex = try? NSRegularExpression(pattern: "```nanomuse-([a-z-]+)[ \\t]*\\r?\\n([\\s\\S]*?)```") else {
            return [.prose(text)]
        }
        var out: [Piece] = []
        let ns = text as NSString
        var cursor = 0
        for match in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let before = ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !before.isEmpty { out.append(.prose(before)) }
            out.append(.block(kind: ns.substring(with: match.range(at: 1)), json: ns.substring(with: match.range(at: 2)).trimmingCharacters(in: .whitespacesAndNewlines)))
            cursor = match.range.location + match.range.length
        }
        let tail = ns.substring(from: cursor).trimmingCharacters(in: .whitespacesAndNewlines)
        if !tail.isEmpty { out.append(.prose(tail)) }
        return out
    }
}

// MARK: - Session addenda

/// Short system-prompt additions bound to one session for a few turns: the
/// goal-creation instructions, the first conversation's phase, and the
/// like. Kept in UserDefaults so an app restart mid-flow keeps the thread.
/// A draft id is rebound to the real session id when the first message
/// lands (`rebind`).
enum NanoMuseSessionAddenda {
    private static let key = "nanomuse.addenda"

    struct Entry: Codable, Equatable {
        var tag: String
        var text: String
        /// Turns left; nil = until removed.
        var turns: Int?
    }

    private static func all() -> [String: [Entry]] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let map = try? JSONDecoder().decode([String: [Entry]].self, from: data) else { return [:] }
        return map
    }

    private static func save(_ map: [String: [Entry]]) {
        let trimmed = map.filter { !$0.value.isEmpty }
        if trimmed.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else if let data = try? JSONEncoder().encode(trimmed) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func add(session: String, tag: String, text: String, turns: Int?) {
        var map = all()
        var list = map[session] ?? []
        list.removeAll { $0.tag == tag }
        list.append(Entry(tag: tag, text: text, turns: turns))
        map[session] = list
        save(map)
    }

    static func remove(session: String, tag: String) {
        var map = all()
        map[session]?.removeAll { $0.tag == tag }
        save(map)
    }

    static func has(session: String, tag: String) -> Bool {
        all()[session]?.contains { $0.tag == tag } ?? false
    }

    /// What goes under the system prompt for this session, or nil.
    static func forPrompt(session: String) -> String? {
        let list = all()[session] ?? []
        guard !list.isEmpty else { return nil }
        return list.map(\.text).joined(separator: "\n\n")
    }

    /// A turn finished: count it down; entries at zero go.
    static func onTurnFinished(session: String) {
        var map = all()
        guard var list = map[session] else { return }
        list = list.compactMap { entry in
            guard let turns = entry.turns else { return entry }
            var next = entry
            next.turns = turns - 1
            return turns - 1 <= 0 ? nil : next
        }
        map[session] = list
        save(map)
    }

    /// The draft became a real session.
    static func rebind(from draft: String, to real: String) {
        guard draft != real else { return }
        var map = all()
        guard let list = map.removeValue(forKey: draft) else { return }
        map[real] = (map[real] ?? []) + list
        save(map)
    }
}

// MARK: - Region

/// The rule behind "which way on do we show first": a person in mainland
/// China gets Alibaba Cloud Bailian first; everyone else OpenRouter. See
/// docs/contracts (C5): UI in Simplified Chinese, or signed in with a phone
/// number, or the relay says `region: "cn"`.
enum NanoMuseRegion {
    static func isMainland(languageCode: String?, scriptCode: String?, regionCode: String?, signedInWithPhone: Bool, relayRegion: String?) -> Bool {
        if signedInWithPhone { return true }
        if relayRegion?.lowercased() == "cn" { return true }
        guard languageCode?.lowercased() == "zh" else { return false }
        if scriptCode?.lowercased() == "hans" { return true }
        if let region = regionCode?.uppercased(), region == "CN" { return true }
        // "zh" with neither a script nor a region (the in-app picker's plain zh-Hans tag) reads as mainland.
        return scriptCode == nil && regionCode == nil
    }

    /// The live answer for this phone.
    @MainActor
    static var isMainland: Bool {
        let tag = AppBundle.current.preferredLocalizations.first ?? Locale.preferredLanguages.first ?? "en"
        let locale = Locale(identifier: tag)
        return isMainland(
            languageCode: locale.language.languageCode?.identifier,
            scriptCode: locale.language.script?.identifier,
            regionCode: locale.language.region?.identifier,
            signedInWithPhone: NanoMuseCloud.account?.channel == "phone",
            relayRegion: UserDefaults.standard.string(forKey: "nanomuse.relay.region")
        )
    }
}
