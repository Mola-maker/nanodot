//
//  NanoMuseProviderReach.swift
//  Nanodot
//
//  Why a provider did not answer, read off the error the model client raised, so the chat
//  shows a card that says what happened and what helps instead of the transport's own words
//  ("Could not connect to the server.", "A server with the specified hostname could not be
//  found."). The Android twin is io.github.nanomuse.net.ProviderReach; the kinds, the
//  canonical line and the HTTP rules are the same so a persisted error reads the same way on
//  both phones.
//
//  Two sources feed it. Most failures are classified from the text ([classify]); the host
//  comes from a `[host: …]` tag LLMError appends to a network error (the failing URL is in the
//  NSError, the localized description drops it). The ones whose text upstream flattens — a
//  401/403 becomes "Invalid API key", a 429 "Rate limited" — are kept aside by
//  NanoMuseReachSignal on their way through `mapHTTPError`, and the view model turns them
//  into a canonical line the classifier reads back.
//

import Foundation

enum NanoMuseProviderReach {
    enum Kind: String, Sendable {
        case unreachable
        case regionBlocked = "region_blocked"
        case signedOut = "signed_out"
        case quota
        case rateLimited = "rate_limited"
        /// nanoMuse Cloud refused the turn (`nm_relay:` line; NanoMuseRelayRefusal): `detail` is the whole line, the card is NanoMuseRelayRefusalCard.
        case relay
    }

    struct Reach: Equatable, Sendable {
        var kind: Kind
        /// The host that did not answer, lower-case; empty when the text named none.
        var host: String
        /// What the vendor said, when it said something worth repeating; else the raw line.
        var detail: String = ""
        /// How long the vendor asked us to wait, when it said (`Retry-After`).
        var retryAfterS: Int? = nil

        var isChatGPTPlan: Bool { NanoMuseProviderReach.isPlanHost(host) }
    }

    /// The hosts of the ChatGPT plan: the Codex backend and the token endpoint.
    static let planHosts: Set<String> = ["chatgpt.com", "auth.openai.com"]
    static let regionCode = "unsupported_country_region_territory"

    private static let canonicalPrefix = "nm_reach:"
    private static let quotaMarkers = [
        "usage_limit_reached", "usage_not_included", "insufficient_quota", "quota_exceeded",
        "plan_limit", "exceeded your current quota", "reached your usage limit", "usage limit",
    ]
    private static let offlineWords = [
        "internet connection appears to be offline", "not connected to the internet",
        "could not connect to the server", "network connection was lost",
        "server with the specified hostname could not be found", "cannot find host",
        "connection refused", "connection reset", "network is unreachable", "unexpected end of stream",
        "stream was reset", "broken pipe", "connection abort",
    ]
    private static let timeoutWords = ["timed out", "timeout", "no response from server"]
    private static let tlsWords = ["ssl", "tls", "handshake", "certificate", "secure connection"]

    static func isPlanHost(_ host: String) -> Bool {
        let h = host.lowercased()
        return planHosts.contains { h == $0 || h.hasSuffix("." + $0) }
    }

    /// The `[host: …]` tag LLMError appends to a network error's description, from the
    /// failing URL in the NSError; empty when there is none.
    static func hostTag(_ error: Error) -> String {
        let ns = error as NSError
        var host: String?
        if let url = ns.userInfo[NSURLErrorFailingURLErrorKey] as? URL { host = url.host }
        else if let s = ns.userInfo[NSURLErrorFailingURLStringErrorKey] as? String { host = URL(string: s)?.host }
        guard let h = host?.lowercased(), !h.isEmpty else { return "" }
        return " [host: \(h)]"
    }

    /// The failure behind `text`, or nil when it is not one this card is for. `hintHost` is
    /// the host of the provider the turn ran on, for the sentences that name none.
    static func classify(_ text: String, hintHost: String? = nil) -> Reach? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return nil }
        if NanoMuseRelayRefusal.isCanonical(t) { return Reach(kind: .relay, host: "", detail: t) }
        if let c = parseCanonical(t) { return c }
        let low = t.lowercased()
        let hint = (hintHost ?? "").lowercased()
        let named = hostIn(t) ?? hint

        if low.contains(regionCode) { return Reach(kind: .regionBlocked, host: named, detail: vendorMessage(t)) }
        if quotaMarkers.contains(where: { low.contains($0) }) { return Reach(kind: .quota, host: named, detail: vendorMessage(t)) }

        let network = low.hasPrefix("network error")
            || offlineWords.contains(where: { low.contains($0) })
            || timeoutWords.contains(where: { low.contains($0) })
            || tlsWords.contains(where: { low.contains($0) })
        guard network else { return nil }
        // a provider's 5xx carries a status; a transport failure never does
        let hasStatus = ["500", "502", "503", "504", "529"].contains { low.range(of: "\\b\($0)\\b", options: .regularExpression) != nil }
        if hasStatus && !timeoutWords.contains(where: { low.contains($0) }) { return nil }
        return Reach(kind: .unreachable, host: named, detail: t)
    }

    /// The side channel's record as a reach, when the status and the body say it is one; the
    /// ordinary "invalid key on an API-key provider" stays nil and keeps upstream's text.
    static func fromHTTP(status: Int, body: String?, host: String, oauth: Bool, retryAfterS: Int? = nil) -> Reach? {
        let b = body ?? ""
        let low = b.lowercased()
        let h = host.lowercased()
        if status == 403, low.contains(regionCode) { return Reach(kind: .regionBlocked, host: h, detail: vendorMessage(b)) }
        if [403, 404, 503].contains(status), looksLikeHTML(b) { return Reach(kind: .unreachable, host: h) }
        if status == 401, oauth { return Reach(kind: .signedOut, host: h) }
        if status == 429, quotaMarkers.contains(where: { low.contains($0) }) {
            return Reach(kind: .quota, host: h, detail: vendorMessage(b), retryAfterS: retryAfterS)
        }
        if status == 429, isPlanHost(h) { return Reach(kind: .rateLimited, host: h, retryAfterS: retryAfterS) }
        return nil
    }

    /// `nm_reach:<kind>:<host>:<retry_after>|<detail>` — what the view model stores for a side-channel reach.
    static func canonical(_ r: Reach) -> String {
        let retry = r.retryAfterS.map(String.init) ?? ""
        return canonicalPrefix + r.kind.rawValue + ":" + r.host + ":" + retry + "|" + r.detail.replacingOccurrences(of: "\n", with: " ")
    }

    private static func parseCanonical(_ t: String) -> Reach? {
        guard t.hasPrefix(canonicalPrefix) else { return nil }
        let rest = t.dropFirst(canonicalPrefix.count)
        let head: Substring
        let detail: String
        if let bar = rest.firstIndex(of: "|") {
            head = rest[rest.startIndex..<bar]
            detail = String(rest[rest.index(after: bar)...])
        } else {
            head = rest
            detail = ""
        }
        let parts = head.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2, let kind = Kind(rawValue: parts[0]) else { return nil }
        let retry = parts.count > 2 ? Int(parts[2]) : nil
        return Reach(kind: kind, host: parts[1], detail: detail, retryAfterS: retry)
    }

    static func looksLikeHTML(_ body: String) -> Bool {
        let head = body.drop(while: { $0.isWhitespace }).prefix(64).lowercased()
        return head.hasPrefix("<!doctype html") || head.hasPrefix("<html")
    }

    private static func hostIn(_ t: String) -> String? {
        if let r = t.range(of: "\\[host: ([A-Za-z0-9.\\-]+)\\]", options: .regularExpression) {
            let inner = t[r].dropFirst("[host: ".count).dropLast()
            return String(inner).lowercased()
        }
        if let r = t.range(of: "failed to connect to ([A-Za-z0-9.\\-]+)", options: .regularExpression) {
            return String(t[r].dropFirst("failed to connect to ".count)).lowercased()
        }
        if let r = t.range(of: "https?://([A-Za-z0-9.\\-]+)", options: .regularExpression) {
            let s = t[r]
            return String(s[s.range(of: "://")!.upperBound...]).lowercased()
        }
        return nil
    }

    /// The `error.message` of an OpenAI-shaped body inside `t`, or empty. Also read by the
    /// vendor sheet when a key is refused at save time.
    static func vendorMessage(_ t: String) -> String {
        guard let start = t.firstIndex(of: "{"), let data = String(t[start...]).data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        let err = json["error"] as? [String: Any] ?? json
        guard let m = err["message"] as? String, !m.isEmpty else { return "" }
        return String(m.prefix(300))
    }

    /// "2 h 5 min" / "40 min", for a Retry-After.
    static func duration(_ seconds: Int) -> String {
        let minutes = (seconds + 59) / 60
        let h = minutes / 60
        let m = minutes % 60
        if h > 0 { return String(format: AppLocalized("%d h %d min"), h, m) }
        return String(format: AppLocalized("%d min"), max(m, 1))
    }
}

/// The HTTP failures upstream flattens, kept for the chat for a few seconds: the status, the
/// body and the host, noted in `mapHTTPError` before "Invalid API key" / "Rate limited" is all
/// that is left. `takeFresh` is consumed by the view model when the error is about to be shown.
final class NanoMuseReachSignal: @unchecked Sendable {
    static let shared = NanoMuseReachSignal()

    private let lock = NSLock()
    private var last: (reach: NanoMuseProviderReach.Reach, at: Date)?

    /// Call for every non-2xx answer of a model request; only the ones the card is for are kept.
    func noteHTTPError(status: Int, body: String?, host: String, oauth: Bool, retryAfter: String? = nil) {
        // nanoMuse Cloud's refusals (413 too large, the allowance, the operator's switches …) have a card of their own
        if NanoMuseRelaySignal.shared.noteHTTPError(status: status, body: body, host: host) { return }
        let retry = retryAfter.flatMap { Double($0.trimmingCharacters(in: .whitespaces)) }.map { Int($0) }
        guard let reach = NanoMuseProviderReach.fromHTTP(status: status, body: body, host: host, oauth: oauth, retryAfterS: retry) else { return }
        lock.lock(); defer { lock.unlock() }
        last = (reach, Date())
    }

    /// The reach behind the error the chat is about to show, if there is a fresh one; consumed.
    func takeFresh(maxAge: TimeInterval = 30) -> NanoMuseProviderReach.Reach? {
        lock.lock(); defer { lock.unlock() }
        guard let n = last else { return nil }
        last = nil
        return Date().timeIntervalSince(n.at) <= maxAge ? n.reach : nil
    }

    /// What the chat stores for a failed turn: the canonical line of a fresh side-channel
    /// reach, else `raw` as it came. Called from `friendlyErrorMessage`.
    func lineForError(_ raw: String) -> String? {
        if let fresh = NanoMuseRelaySignal.shared.takeFresh() {
            // the relay refused the turn: the stored line is ours, and an allowance refusal pins the card
            let refusal = fresh.refusal
            let facts = fresh.facts
            if refusal.isAllowance {
                Task { @MainActor in NanoMuseAllowance.shared.refused(refusal, facts: facts) }
            } else if refusal.kind == .signedOut {
                Task { _ = try? await NanoMuseCloud.refresh() } // the key is gone: the account page says so
            }
            return NanoMuseRelayRefusal.canonical(refusal)
        }
        guard let reach = takeFresh() else { return nil }
        return NanoMuseProviderReach.canonical(reach)
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        last = nil
    }
}
