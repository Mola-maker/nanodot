import Foundation

/// The app's own links have two names. Inside, they are `minis://…`: the system prompt, the
/// sandbox, Markdown in a reply and every view speak that vocabulary, which is upstream's and must
/// not change. Towards the system (Info.plist, the share extension) the app is `nanodot://`, so a
/// phone that also has OpenMinis installed never has two apps claiming one scheme. A `nanodot://`
/// link arriving from the system is read as its `minis://` form, nothing else changes.
enum NanoMuseOwnScheme {
    /// What Info.plist registers; what the share extension opens.
    static let system = "nanodot"
    /// The MCP OAuth callback scheme Info.plist registers (the default redirect is a loopback URL).
    static let systemMCP = "nanodot-mcp"
    /// Upstream's in-app vocabulary; never registered with the system.
    static let internalScheme = "minis"

    /// Both names are ours; `nanomuse-mcp` is a different callback and not a link of the app's.
    static func isOwn(_ scheme: String?) -> Bool {
        guard let s = scheme?.lowercased() else { return false }
        return s == internalScheme || s == system || s == "nanomuse"
    }

    /// `nanomuse://x` reads as `minis://x`; any other URL is returned as it came.
    static func internalURL(_ url: URL) -> URL {
        let text = url.absoluteString
        for scheme in [system, "nanomuse"] {
            if let range = text.range(of: scheme + "://", options: [.anchored, .caseInsensitive]) {
                return URL(string: internalScheme + "://" + text[range.upperBound...]) ?? url
            }
        }
        return url
    }

    /// Opens one of the app's own links without going through the system: the deep-link router
    /// handles it as if it had arrived from outside. For a button that sits outside the chat's
    /// `openURL` override (the chat's own views use `\.openMinisURL`).
    static func open(_ link: String) {
        guard let url = URL(string: link) else { return }
        Task { @MainActor in
            DeepLinkRouter.handle(url: url, shareCoordinator: ShareCoordinator.shared)
        }
    }
}
