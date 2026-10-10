//
//  NanoMuseComposerCheck.swift
//  Nanodot
//
//  Settings → Appearance → Composer check: what the device knows about the
//  chat's composer, readable without a Mac. Four rounds of "the input field
//  does not show" were fixed from Linux against screenshots, with nobody able
//  to capture the view hierarchy on the phone; this page is that capture,
//  made by the app itself. A switch draws a red frame around the composer
//  column (the probe view in its background, NanoMuseComposerWatch) so a
//  screenshot shows where SwiftUI laid the column out, and the report says
//  the same in numbers: the window and its safe area, the column's frame,
//  every UIKit ancestor of the probe with its frame, hidden flag and alpha,
//  the text fields and collection views in the window, and the watch's
//  events. Copy goes to the pasteboard, for a bug report.
//

import SwiftUI
import UIKit

struct NanoMuseComposerCheckView: View {
    @State private var report = ""
    @State private var outline = false
    @State private var copied = false

    var body: some View {
        NanoMusePage(title: AppLocalized("Composer check")) {
            VStack(alignment: .leading, spacing: 0) {
                NanoMuseCard {
                    NanoMuseToggleRow(title: AppLocalized("Outline the composer in red"), isOn: $outline)
                    NanoMuseRowDivider()
                    NanoMuseActionRow(title: copied ? AppLocalized("Copied") : AppLocalized("Copy"), chevron: false) {
                        UIPasteboard.general.string = report
                        copied = true
                    }
                    NanoMuseRowDivider()
                    NanoMuseActionRow(title: AppLocalized("Refresh"), chevron: false) {
                        refresh()
                    }
                }
                NanoMuseCaption(text: AppLocalized("For a chat whose input field does not show. Turn the outline on, go back to the chat and take a screenshot: the red frame is where the app laid the composer out. The report below says the same in numbers; copy it into the bug report."))
            }
            NanoMuseCard {
                Text(report)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
            }
        }
        .onAppear {
            outline = NanoMuseComposerWatch.outline
            refresh()
        }
        .nmOnChange(of: outline) { on in
            NanoMuseComposerWatch.outline = on
            refresh()
        }
    }

    private func refresh() {
        report = NanoMuseComposerReport.build()
        copied = false
    }
}

/// The report's text: the app, the windows, the watch's state, the column's UIKit frame and
/// ancestry, the fields and lists in the window, the events.
@MainActor
enum NanoMuseComposerReport {
    static func build() -> String {
        var lines: [String] = []
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        let device = UIDevice.current
        let idiom = device.userInterfaceIdiom == .pad ? "pad" : "phone"
        lines.append("Nanodot \(version) (\(build)) · \(device.systemName) \(device.systemVersion) · \(device.model) · \(idiom)")
        lines.append("Muse home: \(NanoMuseShellPrefs.shell ? "on" : "off") · outline: \(NanoMuseComposerWatch.outline ? "on" : "off")")

        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            for window in scene.windows where !window.isHidden {
                lines.append("window \(name(of: window)) key=\(window.isKeyWindow) frame=\(rect(window.frame)) safe=\(insets(window.safeAreaInsets)) level=\(Int(window.windowLevel.rawValue))")
            }
        }

        guard let watch = NanoMuseComposerWatch.current else {
            lines.append("no chat has been on screen since the app started")
            return lines.joined(separator: "\n")
        }
        lines.append("watch: visible=\(watch.visible) attached=\(watch.attachedCount) columnH=\(one(watch.hostHeight)) barH=\(one(watch.frameHeight)) rebuilds=\(watch.rebuildTick)")

        if let probe = watch.probe {
            if let window = probe.window {
                let column = probe.convert(probe.bounds, to: window)
                let safeBottom = window.bounds.maxY - window.safeAreaInsets.bottom
                lines.append("composer column in the window: \(rect(column)); the window ends at \(one(window.bounds.maxY)), its safe bottom is \(one(safeBottom))")
                lines.append("verdict: \(verdict(column: column, window: window))")
                lines.append("ancestors of the probe, nearest first:")
                var view: UIView? = probe.superview
                var depth = 0
                while let current = view, depth < 48 {
                    lines.append("  \(name(of: current)) \(rect(current.convert(current.bounds, to: window))) hidden=\(current.isHidden) alpha=\(one(current.alpha)) clips=\(current.clipsToBounds) subviews=\(current.subviews.count)")
                    view = current.superview
                    depth += 1
                }
                let all = descendants(of: window)
                lines.append("text fields and text views in the window:")
                let fields = all.filter { $0 is UITextField || $0 is UITextView }
                if fields.isEmpty { lines.append("  none") }
                for field in fields {
                    lines.append("  \(name(of: field)) \(rect(field.convert(field.bounds, to: window))) hidden=\(field.isHidden) alpha=\(one(field.alpha)) firstResponder=\(field.isFirstResponder)")
                }
                lines.append("collection views in the window:")
                let lists = all.compactMap { $0 as? UICollectionView }
                if lists.isEmpty { lines.append("  none") }
                for list in lists {
                    lines.append("  \(name(of: list)) \(rect(list.convert(list.bounds, to: window))) inset.bottom=\(one(list.contentInset.bottom)) adjusted.bottom=\(one(list.adjustedContentInset.bottom)) safe.bottom=\(one(list.safeAreaInsets.bottom)) contentH=\(one(list.contentSize.height)) offsetY=\(one(list.contentOffset.y))")
                }
            } else {
                lines.append("the probe exists but is in no window: the composer column is not on screen")
            }
        } else {
            lines.append("no probe yet: the composer column has never been laid out in this chat")
        }

        lines.append("events, oldest first:")
        if watch.events.isEmpty { lines.append("  none") }
        for event in watch.events { lines.append("  \(event)") }
        return lines.joined(separator: "\n")
    }

    /// One line a person can act on, from the column's frame alone.
    private static func verdict(column: CGRect, window: UIWindow) -> String {
        let safeBottom = window.bounds.maxY - window.safeAreaInsets.bottom
        if column.height < 20 { return "the column has no height to speak of; nothing inside it took space" }
        if column.minY >= window.bounds.maxY { return "the column is below the window, off screen" }
        if column.maxY > safeBottom + 1 { return "the column reaches below the window's safe bottom, under the home indicator or off screen" }
        if NanoMuseShellPrefs.shell, column.maxY > safeBottom - 56 + 1 {
            return "the column ends behind the Muse bottom bar (the last 56 pt above the safe bottom); hidden by it unless the keyboard is up"
        }
        if column.width < 100 { return "the column is too narrow to show the pill" }
        return "the column is laid out inside the window, above the bottom bar; if nothing shows there, what is inside it is not drawing"
    }

    private static func descendants(of view: UIView) -> [UIView] {
        var out: [UIView] = []
        for sub in view.subviews {
            out.append(sub)
            out.append(contentsOf: descendants(of: sub))
        }
        return out
    }

    private static func name(of view: UIView) -> String {
        String(describing: type(of: view))
    }

    private static func rect(_ r: CGRect) -> String {
        "x\(one(r.minX)) y\(one(r.minY)) \(one(r.width))×\(one(r.height))"
    }

    private static func insets(_ i: UIEdgeInsets) -> String {
        "t\(one(i.top)) l\(one(i.left)) b\(one(i.bottom)) r\(one(i.right))"
    }

    private static func one(_ value: CGFloat) -> String {
        NanoMuseComposerWatch.one(value)
    }
}
