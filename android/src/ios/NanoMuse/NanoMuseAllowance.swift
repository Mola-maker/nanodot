//
//  NanoMuseAllowance.swift
//  Nanodot
//
//  The free allowance as the chat sees it (docs/parity.md, item 33). Two things, kept here
//  so the shell and the composer can show them without a link of their own on
//  `AIChatView.body`: the card pinned under the header when a turn was refused for the
//  allowance — spent, paused by the operator, or today's share — with the ways on and
//  *Try again*; and the one-line heads-up above the composer when the relay says `warn`
//  (80 % spent), once per pool size. The relay's guidance for the region (`spend.guidance`,
//  contract C11) is kept between runs so the ways on read the relay's list first, the
//  bundled catalogue only when it sent none. Android: AllowanceSignal + AllowanceWaysCard.
//

import Combine
import Foundation

@MainActor
final class NanoMuseAllowance: ObservableObject {
    static let shared = NanoMuseAllowance()

    /// A turn refused for the allowance, with what the relay said beside the code.
    struct Refused: Equatable, Identifiable {
        var kind: NanoMuseRelayRefusal.Kind
        var facts: NanoMuseRelayRefusal.AllowanceFacts
        var at: Date
        var id: Double { at.timeIntervalSince1970 }

        var paused: Bool { kind == .allowancePaused }
        var dailyCap: Bool { kind == .dailyCap }
    }

    private enum Keys {
        static let guidance = "nanomuse.cloud.guidance"
        static let warnedGrant = "nanomuse.cloud.warned_grant"
        static let warnHidden = "nanomuse.cloud.warn_hidden"
    }

    /// The card the shell should pin now; nil when there is none.
    @Published private(set) var pending: Refused?
    /// The 80 % line above the composer; nil when there is none or it was waved away.
    @Published private(set) var headsUp: String?

    private init() {}

    // MARK: The refused turn

    /// A model call came back with an allowance refusal (from `NanoMuseRelaySignal`, through
    /// `lineForError`); the card goes up under the header, over the chat that was open.
    func refused(_ refusal: NanoMuseRelayRefusal.Refusal, facts: NanoMuseRelayRefusal.AllowanceFacts?) {
        guard refusal.isAllowance else { return }
        var f = facts ?? NanoMuseRelayRefusal.AllowanceFacts()
        if f.guidance == nil { f.guidance = Self.storedGuidance() } else { Self.store(guidance: f.guidance) }
        pending = Refused(kind: refusal.kind, facts: f, at: Date())
        headsUp = nil // the card says more than the line
    }

    func dismiss() { pending = nil }

    /// *Try again* on the card: the open chat's last turn once more (the shell names it), and the card goes.
    func retry(session: String?) {
        pending = nil
        guard let session, let vm = ViewModelCache.shared.get(for: session) else { return }
        vm.retry()
    }

    // MARK: /v1/me

    /// Every `/v1/me`: the guidance is kept for the ways on, and the 80 % heads-up is raised
    /// once per pool size (`spend.warn`; the grant is the size). A pool that is spent or
    /// paused has the card instead, when a turn is refused.
    func absorb(me: [String: Any]) {
        guard let spend = me["spend"] as? [String: Any] else { return }
        if let g = NanoMuseGuidance.parse(spend["guidance"] as? [String: Any]) { Self.store(guidance: g) }
        func num(_ v: Any?) -> Double? {
            if let n = v as? NSNumber { return n.doubleValue }
            if let s = v as? String { return Double(s) }
            return nil
        }
        let warn = (spend["warn"] as? Bool) ?? ((spend["warn"] as? NSNumber)?.boolValue ?? false)
        let unlimited = (spend["unlimited"] as? Bool) ?? ((spend["unlimited"] as? NSNumber)?.boolValue ?? false)
        let grant = num(spend["grant"]) ?? 0
        let left = num(spend["left"]) ?? max(grant - (num(spend["total"]) ?? 0), 0)
        let d = UserDefaults.standard
        guard warn, !unlimited, grant > 0, left > 0 else {
            if !warn { d.removeObject(forKey: Keys.warnHidden) } // the next time it passes 80 % is a new time
            headsUp = nil
            return
        }
        let warned = d.double(forKey: Keys.warnedGrant)
        if abs(warned - grant) > 0.005 {
            // a new pool size (an invitation landed, the relay's grant changed): say it once more
            d.set(grant, forKey: Keys.warnedGrant)
            d.removeObject(forKey: Keys.warnHidden)
        }
        guard !d.bool(forKey: Keys.warnHidden) else { headsUp = nil; return }
        headsUp = String(format: AppLocalized("Nearly used up: ¥%@ of ¥%@ of the free allowance left. A key of your own or a plan you already pay for keeps you going, under Settings → nanoMuse Cloud."), Self.money(left), Self.money(grant))
    }

    /// The heads-up was waved away: not again for this pool size.
    func hideHeadsUp() {
        UserDefaults.standard.set(true, forKey: Keys.warnHidden)
        headsUp = nil
    }

    /// Signed out: nothing of the account's stays.
    func forget() {
        pending = nil
        headsUp = nil
        let d = UserDefaults.standard
        d.removeObject(forKey: Keys.guidance)
        d.removeObject(forKey: Keys.warnedGrant)
        d.removeObject(forKey: Keys.warnHidden)
    }

    // MARK: The guidance between runs

    /// The relay's last guidance for this account, or nil (an older relay, or none yet).
    static func storedGuidance() -> NanoMuseGuidance? {
        guard let json = UserDefaults.standard.string(forKey: Keys.guidance) else { return nil }
        return NanoMuseGuidance.parse(json: json)
    }

    private static func store(guidance: NanoMuseGuidance?) {
        guard let guidance else { return }
        // kept as the relay's own JSON shape, so the parser is the one used on the wire
        var providers: [[String: Any]] = []
        for v in guidance.providers { providers.append(encode(v)) }
        var local: [[String: Any]] = []
        for v in guidance.local { local.append(encode(v)) }
        let plans: [[String: Any]] = guidance.plans.map {
            ["id": $0.id, "provider": $0.provider, "name": $0.name, "auth": $0.auth, "clients": Array($0.clients).sorted(), "covers": Array($0.covers).sorted()]
        }
        let o: [String: Any] = [
            "region": guidance.region, "docs": guidance.docs, "providers": providers, "plans": plans, "local": local,
            "caveats": ["chatgpt": guidance.chatgptCaveat, "chatgpt_zh": guidance.chatgptCaveatZh],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: o), let json = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(json, forKey: Keys.guidance)
    }

    private static func encode(_ v: NanoMuseVendor) -> [String: Any] {
        var o: [String: Any] = [
            "id": v.id, "name": v.name, "name_zh": v.nameZh, "protocol": v.protocolName, "base_url": v.baseURL,
            "key_url": v.keyURL, "auth": v.auth, "regions": Array(v.regions).sorted(), "covers": Array(v.capabilities).sorted(),
            "defaults": v.defaults, "note": v.note, "note_zh": v.noteZh, "verified": v.verified,
        ]
        if let g = v.baseURLGlobal { o["base_url_global"] = g }
        if let g = v.keyURLGlobal { o["key_url_global"] = g }
        if let h = v.keyHint { o["key_hint"] = h }
        if v.userCapabilities { o["user_capabilities"] = true }
        if !v.authCapabilities.isEmpty { o["auth_capabilities"] = v.authCapabilities.mapValues { Array($0).sorted() } }
        return o
    }

    /// "5.00" / "0.37": yuan to two places.
    static func money(_ n: Double) -> String { String(format: "%.2f", n) }
}
