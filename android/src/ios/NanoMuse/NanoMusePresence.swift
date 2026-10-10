//
//  NanoMusePresence.swift
//  Nanodot
//
//  Contract C9: while another device of the account works on a synced
//  conversation, this phone says so under that device's last line —
//  "kwai is working…" — instead of upstream's "Interrupted — tap Resume",
//  which is for this phone's own unfinished turns only. The relay keeps the
//  latest `working: true` per (account, cid) for ten minutes and tells the
//  account's other sockets through the hub's `working` frame; this is the
//  small map of those, with the expiry, the best-effort POST this phone
//  makes for its own turns, and the line put on the message it belongs to.
//  Presence is never retried and never blocks anything.
//  Relay: POST /v1/sync/working, GET /v1/sync/state (`working`), hub frame `working`.
//  Android: io.github.nanomuse.sync.Presence; desktop: sync.ts `working`.
//

import Foundation

@MainActor
final class NanoMusePresence: ObservableObject {
    static let shared = NanoMusePresence()

    /// The relay forgets a `working: true` after this long; so does the phone.
    nonisolated static let ttl: TimeInterval = 600

    /// One device at work on one conversation.
    struct Working: Equatable {
        let cid: String
        let from: String
        let deviceName: String
        /// Unix seconds the relay stamped.
        let at: Date
    }

    /// Bumped whenever the map changes (a frame, an expiry); the chat re-reads on it.
    @Published private(set) var revision = 0

    private var working: [String: Working] = [:]
    private var expiry: Task<Void, Never>?
    /// The message each open chat currently shows the line on, so it can be taken off again
    /// without a walk over the whole list.
    private var shown: [String: NanoMuseWeakMessage] = [:]

    private init() {}

    // MARK: Reading

    /// `{cid, from, device_name, working, at}` — a hub frame, or one row of `/v1/sync/state`'s `working`.
    nonisolated static func parse(_ frame: [String: Any]) -> Working? {
        guard let cid = frame["cid"] as? String, !cid.isEmpty, let from = frame["from"] as? String else { return nil }
        let at = (frame["at"] as? Double) ?? Double((frame["at"] as? Int) ?? 0)
        return Working(cid: cid, from: from, deviceName: (frame["device_name"] as? String) ?? "", at: Date(timeIntervalSince1970: at > 0 ? at : Date().timeIntervalSince1970))
    }

    /// Whether a `working` stamped `at` still counts `now`.
    nonisolated static func isLive(at: Date, now: Date = Date(), ttl: TimeInterval = NanoMusePresence.ttl) -> Bool {
        now.timeIntervalSince(at) < ttl
    }

    /// The hub's `working` frame. `working: false` clears; `true` replaces.
    func onHubFrame(_ frame: [String: Any]) {
        guard let entry = Self.parse(frame) else { return }
        if entry.from == NanoMuseHub.shared.deviceId { return }
        let on = (frame["working"] as? Bool) ?? false
        if on { working[entry.cid] = entry } else { working.removeValue(forKey: entry.cid) }
        changed()
    }

    /// `working` from `GET /v1/sync/state`: the unexpired entries the relay still holds.
    func apply(list: [[String: Any]]) {
        let me = NanoMuseHub.shared.deviceId
        var next: [String: Working] = [:]
        for row in list {
            guard let entry = Self.parse(row), entry.from != me, Self.isLive(at: entry.at) else { continue }
            next[entry.cid] = entry
        }
        guard next != working else { return }
        working = next
        changed()
    }

    /// Who is working on `cid` right now, if anyone (expired entries read as nobody).
    func working(for cid: String, now: Date = Date()) -> Working? {
        guard let entry = working[cid] else { return nil }
        return Self.isLive(at: entry.at, now: now) ? entry : nil
    }

    /// C10: another account signed in (or this one signed out) — what the relay said about the
    /// previous account's conversations is forgotten, lines included.
    func reset() {
        for holder in shown.values {
            if let message = holder.message, message.nmWorkingDevice != nil { message.nmWorkingDevice = nil }
        }
        shown = [:]
        guard !working.isEmpty else { return }
        working = [:]
        changed()
    }

    /// Ask the relay once what it remembers (the hub just said welcome, or the chat came back).
    func refresh() {
        guard NanoMuseCloud.isSignedIn, let token = NanoMuseCloud.apiKey else { return }
        Task { @MainActor in
            guard let reply = try? await NanoMuseCloud.call("GET", "/v1/sync/state", body: nil, token: token) else { return }
            apply(list: reply["working"] as? [[String: Any]] ?? [])
        }
    }

    // MARK: Telling

    /// This phone started (or ended) a turn on a synced conversation. One POST, no retry, errors ignored.
    func send(cid: String, working on: Bool) {
        guard NanoMuseCloud.isSignedIn, let token = NanoMuseCloud.apiKey else { return }
        Task { @MainActor in
            _ = try? await NanoMuseCloud.call("POST", "/v1/sync/working", body: ["cid": cid, "working": on], token: token)
        }
    }

    // MARK: The line in the chat

    /// The line a chat should show under its last message: the name of the device at work on
    /// the conversation, when the last message is a user line from another device and presence
    /// says so; nil otherwise. The test form of `nmApplyPresence`.
    nonisolated static func line(lastIsRemoteUser: Bool, lastFromDevice: String?, entry: Working?, me: String) -> String? {
        guard lastIsRemoteUser, let entry, entry.from != me else { return nil }
        return entry.deviceName.isEmpty ? (lastFromDevice ?? "") : entry.deviceName
    }

    /// Remember which message of `session` carries the line, taking it off the previous one.
    func show(_ line: String?, on message: ChatMessage?, session: String) {
        if let previous = shown[session]?.message, previous !== message, previous.nmWorkingDevice != nil {
            previous.nmWorkingDevice = nil
        }
        if let message {
            if message.nmWorkingDevice != line { message.nmWorkingDevice = line }
            shown[session] = line == nil ? nil : NanoMuseWeakMessage(message)
        } else {
            shown[session] = nil
        }
    }

    // MARK: Expiry

    private func changed() {
        revision &+= 1
        expiry?.cancel()
        guard let soonest = working.values.map(\.at).min() else { return }
        let wait = max(1, Self.ttl - Date().timeIntervalSince(soonest) + 0.5)
        expiry = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            self.working = self.working.filter { Self.isLive(at: $0.value.at) }
            self.changed()
        }
    }
}

/// A message held weakly: the line's holder outlives no chat.
final class NanoMuseWeakMessage {
    weak var message: ChatMessage?
    init(_ message: ChatMessage) { self.message = message }
}
