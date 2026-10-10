//
//  NanoMuseComposerWatch.swift
//  Nanodot
//
//  The composer that disappears: the watch behind it, and the evidence for
//  the next report.
//
//  Since 0.1.40 the composer column (the cards, the tool strip, the input
//  bar) is a row under the message list, inside the safe area
//  (NanoMuseComposerHost): one attachment point — no overlay of a UIKit
//  list, no second host to switch to. The watch stays as the last net for
//  the failure upstream saw on the device — a host that survives with no
//  subviews after a sheet, a keyboard or a background pass, while SwiftUI
//  has nothing to re-render for — and it keeps what it sees, so Settings →
//  Appearance → Composer check can show it without a Mac: the probe's last
//  sizes, the rebuilds, the events, and the UIKit frame of the column in
//  the window (NanoMuseComposerCheck.swift).
//
//  How: a probe `UIView` in the column's background says when it is
//  attached to a window and how tall the column really is; the input bar's
//  geometry callback reports every frame, zero included. Whenever one of
//  them looks wrong — detached while the chat is on screen, a zero-height
//  column, a zero frame, no wake-up after a foreground — the state is
//  re-checked a second later and, if still wrong, `rebuildTick` moves.
//  AIChatView puts `.id(tick)` on the column, so SwiftUI tears it down and
//  builds it again: the same recovery as leaving and re-entering the
//  session, without the trip. The text being typed lives in the view model
//  and survives; the focus does not, which is why a healthy composer is
//  never rebuilt, and why the watch rebuilds at most twice per appearance:
//  a column that is fine but mis-measured must not take the keyboard away
//  every few seconds.
//
//  0.1.37–0.1.39 switched to a second host (a bottom safe-area inset) when
//  the overlay reported nothing one second after the chat appeared. Three
//  TestFlight builds later the composer was still missing on the
//  maintainer's iPad — and the fault was never in the composer: the whole
//  chat ran under the shell's bottom bar once the keyboard had come and
//  gone (NanoMuseShell.swift, NanoMuseHomeView.body), so the column sat
//  behind the bar, laid out and healthy by every measure the watch has.
//  There is one host now, the bar is a row, and the check page says what
//  the device sees.
//

import os
import SwiftUI
import UIKit

@MainActor
final class NanoMuseComposerWatch: ObservableObject {
    /// One line of the field, so the pill never collapses to its padding when the field
    /// reports nothing. Equal to the field's natural one-line height, so the healthy
    /// composer looks exactly as before.
    static var fieldFloor: CGFloat {
        UIFont.systemFont(ofSize: FontSettings.shared.scaledChatInput(16.5)).lineHeight.rounded(.up)
    }

    private static let log = Logger(subsystem: "io.github.nanodot.app", category: "nm.composer")
    /// How long a wrong state may last before the column is rebuilt.
    // nonisolated: read as a default argument (a nonisolated context); Xcode 27's compiler warns otherwise
    private nonisolated static let patience: TimeInterval = 1.0
    /// Two rebuilds are never closer than this.
    private static let cooldown: TimeInterval = 3.0
    /// Rebuilds per appearance of the chat, at most.
    private static let rebuildCap = 2
    /// Events the check page keeps.
    private static let eventCap = 80

    /// The watch of the chat that appeared last; the check page reads it.
    private(set) static weak var current: NanoMuseComposerWatch?

    /// The check page's switch: the probe draws a red frame around the composer column.
    static var outline = false {
        didSet { current?.probe?.applyOutline() }
    }

    /// AIChatView keys the composer column on this.
    @Published private(set) var rebuildTick = 0

    /// Whether the chat view is on screen (its onAppear / onDisappear).
    var visible = false {
        didSet {
            if visible {
                Self.current = self
                rebuilds = 0
            } else {
                check?.cancel()
                expectation?.cancel()
            }
            note(visible ? "the chat appeared" : "the chat left the screen")
        }
    }

    private var attached: Set<ObjectIdentifier> = []
    /// Probe views in a window right now (one, while the column is on screen).
    var attachedCount: Int { attached.count }
    /// The column's height as the probe last laid it out.
    private(set) var hostHeight: CGFloat = 0
    /// The last height the input bar's geometry callback reported; -1 until the first.
    private(set) var frameHeight: CGFloat = -1
    /// The latest probe, for the check page's walk of the UIKit hierarchy.
    private(set) weak var probe: NanoMuseComposerProbeView?
    /// What happened, newest last, for the check page.
    private(set) var events: [String] = []

    private var check: Task<Void, Never>?
    private var expectation: Task<Void, Never>?
    private var lastRebuild: CFAbsoluteTime = 0
    private var rebuilds = 0

    /// Pure form of the rule, for the tests: no probe in a window, a column of no height, or
    /// an input bar that never reported a height (or reported 0).
    nonisolated static func isMissing(attached: Int, hostHeight: CGFloat, frameHeight: CGFloat) -> Bool {
        attached == 0 || hostHeight <= 0 || frameHeight <= 0
    }

    // MARK: Signals

    /// The input bar's `onGeometryChange`, every sample (upstream drops the zero ones before use).
    func geometry(height: CGFloat) {
        if abs(height - frameHeight) > 0.5 { note("input bar frame \(Self.one(height)) pt") }
        frameHeight = height
        if height <= 0, visible { schedule("zero-height input bar frame") }
    }

    /// The probe view entered a window.
    func probeAttached(_ view: NanoMuseComposerProbeView) {
        attached.insert(ObjectIdentifier(view))
        probe = view
        note("probe attached to a window (\(attached.count) attached)")
    }

    /// The probe view left its window — the column is being torn down (or the chat is leaving).
    func probeDetached(_ view: NanoMuseComposerProbeView) {
        attached.remove(ObjectIdentifier(view))
        note("probe left its window (\(attached.count) attached)")
        if attached.isEmpty, visible { schedule("composer column detached from the window") }
    }

    /// The probe laid out: its bounds are the composer column's.
    func probeLaidOut(height: CGFloat) {
        if abs(height - hostHeight) > 0.5 { note("composer column laid out \(Self.one(height)) pt tall") }
        hostHeight = height
        if height <= 0, visible { schedule("zero-height composer column") }
    }

    /// The scene came back to the front: iOS may have torn the column down while snapshotting.
    func sceneActive() {
        if visible { schedule("scene active", after: Self.patience + 0.3) }
    }

    /// Upstream's health probe found no geometry callback after a foreground.
    func stalled() {
        note("upstream: no geometry callback 900 ms after the foreground")
        if visible { schedule("no geometry after foreground", after: 0.2) }
    }

    /// The chat appeared, or a message went out: a composer must be on screen a second from
    /// now. If it is not — nothing attached, no height, no frame — the column is rebuilt.
    func expect(_ reason: String) {
        guard visible else { return }
        expectation?.cancel()
        expectation = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(NanoMuseComposerWatch.patience * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.confirm(reason)
        }
    }

    // MARK: The check

    private func schedule(_ reason: String, after delay: TimeInterval = NanoMuseComposerWatch.patience) {
        check?.cancel()
        check = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.verify(reason)
        }
    }

    private func verify(_ reason: String) {
        guard visible else { return }
        if !attached.isEmpty && hostHeight > 0 {
            if frameHeight == 0 {
                note("check (\(reason)): the column is up (\(Self.one(hostHeight)) pt) although the bar's last frame was 0; left alone")
            }
            return
        }
        let since = CFAbsoluteTimeGetCurrent() - lastRebuild
        if since <= Self.cooldown {
            // 0.1.37 dropped this check; a column that came back empty right after a rebuild
            // then had nothing left to wake it. Look again once the cooldown is over.
            schedule(reason, after: Self.cooldown - since + 0.1)
            return
        }
        rebuild(reason)
    }

    /// The expectation came due: the composer is either there or the column is rebuilt.
    private func confirm(_ reason: String) {
        guard visible else { return }
        let state = "attached=\(attached.count) columnH=\(Self.one(hostHeight)) barH=\(Self.one(frameHeight))"
        if !Self.isMissing(attached: attached.count, hostHeight: hostHeight, frameHeight: frameHeight) {
            note("composer confirmed one second after \(reason) (\(state))")
            return
        }
        Self.log.error("composer missing one second after \(reason, privacy: .public) (\(state, privacy: .public))")
        let since = CFAbsoluteTimeGetCurrent() - lastRebuild
        if since <= Self.cooldown {
            note("composer missing one second after \(reason) (\(state)); inside the rebuild cooldown, checking again after it")
            schedule(reason, after: Self.cooldown - since + 0.1)
            return
        }
        rebuild("missing one second after \(reason) (\(state))")
    }

    private func rebuild(_ reason: String) {
        guard rebuilds < Self.rebuildCap else {
            note("not rebuilding: \(reason); \(rebuilds) rebuilds since the chat appeared is the cap")
            return
        }
        rebuilds += 1
        lastRebuild = CFAbsoluteTimeGetCurrent()
        Self.log.error("composer self-heal: rebuilding the composer column (\(self.rebuilds)/\(NanoMuseComposerWatch.rebuildCap)): \(reason, privacy: .public)")
        note("rebuilding the composer column (\(rebuilds)/\(Self.rebuildCap)): \(reason)")
        attached = []
        hostHeight = 0
        frameHeight = -1
        // Outside any running animation: a view swapped under a transition is one of the ways
        // a host has been seen to come back empty.
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { rebuildTick &+= 1 }
        // The rebuilt column must prove itself.
        expect("the rebuild")
    }

    // MARK: Evidence

    private static let stamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    private func note(_ line: String) {
        events.append("\(Self.stamp.string(from: Date())) \(line)")
        if events.count > Self.eventCap { events.removeFirst(events.count - Self.eventCap) }
        Self.log.info("\(line, privacy: .public)")
    }

    nonisolated static func one(_ value: CGFloat) -> String {
        String(format: "%.1f", value)
    }
}

// MARK: - The probe

/// A clear, non-interactive view in the composer column's background: it tells the watch
/// when the column is in a window and how tall it is laid out, and draws the check page's
/// red frame.
struct NanoMuseComposerProbe: UIViewRepresentable {
    let watch: NanoMuseComposerWatch

    func makeUIView(context: Context) -> NanoMuseComposerProbeView {
        let view = NanoMuseComposerProbeView()
        view.watch = watch
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ uiView: NanoMuseComposerProbeView, context: Context) {
        uiView.watch = watch
    }
}

final class NanoMuseComposerProbeView: UIView {
    weak var watch: NanoMuseComposerWatch?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            watch?.probeAttached(self)
            applyOutline()
        } else {
            watch?.probeDetached(self)
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard window != nil else { return }
        watch?.probeLaidOut(height: bounds.height)
    }

    /// The check page's red frame around the composer column, when its switch is on.
    func applyOutline() {
        let on = NanoMuseComposerWatch.outline
        layer.borderWidth = on ? 2 : 0
        layer.borderColor = on ? UIColor.systemRed.cgColor : nil
    }
}
