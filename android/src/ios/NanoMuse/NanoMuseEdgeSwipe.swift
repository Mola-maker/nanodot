//
//  NanoMuseEdgeSwipe.swift
//  Nanodot
//
//  A swipe in from the leading edge of the main chat opens the side drawer, as Android's
//  ModalNavigationDrawer does on the Chat tab (NanoMuseHome.kt: `gesturesEnabled = … ||
//  tab == HomeTab.CHAT`). Until 0.1.41 the drawer opened from the round hamburger only.
//
//  The gesture is UIKit's screen-edge pan, put on the window the chat lives in: a recognizer
//  on an ancestor sees every touch under it without taking any — taps on the hamburger, the
//  transcript and the composer go where they went, and the transcript scrolls as before. The
//  SwiftUI side says when the swipe may begin (the chat tab, the main chat on top, the drawer
//  closed) and the recognizer adds two checks of its own: the touch starts over the chat layer,
//  and nothing is presented over the window (a sheet, the OpenMinis layout). A side chat pushed
//  on the stack keeps the system's back swipe; the shell turns this one off while it is up.
//

import SwiftUI
import UIKit

/// A clear, non-interactive view in the chat layer's background that owns the edge-pan
/// recognizer on the window above it.
struct NanoMuseEdgeSwipe: UIViewRepresentable {
    /// Whether a swipe may open the drawer right now.
    var enabled: Bool
    var onSwipe: () -> Void

    func makeUIView(context: Context) -> NanoMuseEdgeSwipeView {
        let view = NanoMuseEdgeSwipeView()
        view.enabled = enabled
        view.onSwipe = onSwipe
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ uiView: NanoMuseEdgeSwipeView, context: Context) {
        uiView.enabled = enabled
        uiView.onSwipe = onSwipe
    }

    static func dismantleUIView(_ uiView: NanoMuseEdgeSwipeView, coordinator: ()) {
        uiView.detach()
    }
}

final class NanoMuseEdgeSwipeView: UIView, UIGestureRecognizerDelegate {
    /// How far the finger travels inward before the drawer opens.
    static let threshold: CGFloat = 28

    var enabled = true
    var onSwipe: (() -> Void)?

    private var recognizer: UIScreenEdgePanGestureRecognizer?
    private weak var host: UIWindow?
    /// Set once a swipe opened the drawer, until the finger lifts: one opening per gesture.
    private var fired = false

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if let window {
            if host !== window { detach(); attach(to: window) }
        } else {
            detach()
        }
    }

    private func attach(to window: UIWindow) {
        let edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(panned(_:)))
        edge.edges = effectiveUserInterfaceLayoutDirection == .rightToLeft ? .right : .left
        edge.delegate = self
        edge.cancelsTouchesInView = false
        window.addGestureRecognizer(edge)
        recognizer = edge
        host = window
    }

    /// Take the recognizer off the window again (the view left the window, or SwiftUI let it go).
    func detach() {
        if let recognizer { host?.removeGestureRecognizer(recognizer) }
        recognizer = nil
        host = nil
    }

    @objc private func panned(_ r: UIScreenEdgePanGestureRecognizer) {
        switch r.state {
        case .began:
            fired = false
        case .changed:
            guard !fired else { return }
            let dx = r.translation(in: r.view).x
            let inward = r.edges == .right ? -dx : dx
            if inward >= Self.threshold {
                fired = true
                onSwipe?()
            }
        default:
            fired = false
        }
    }

    // MARK: UIGestureRecognizerDelegate

    /// UIView has this hook for its own recognizers, hence `override`; the window's recognizer
    /// reaches it as its delegate.
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard enabled, let window else { return false }
        // Something presented over the window (a sheet, the OpenMinis layout) owns the edge.
        if window.rootViewController?.presentedViewController != nil { return false }
        // The touch starts over the chat layer, not over the bottom bar or anything else.
        let point = gestureRecognizer.location(in: self)
        return bounds.contains(point)
    }

    /// The transcript keeps scrolling and the composer keeps its own drags; only a pan that
    /// really starts at the edge and heads inward reaches `threshold`.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }
}
