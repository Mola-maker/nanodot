//
//  NanoMuseComposerField.swift
//  Nanodot
//
//  The text field inside Muse's pill, SwiftUI's own. Three rounds of the
//  composer disappearing (0.1.36 the pill, 0.1.37 the watchdog, this one)
//  came down to the same anatomy: the field was upstream's
//  `PastableTextView`, a UIViewRepresentable, and the chat's bottom overlay
//  was therefore hosted by a UIKit container that iOS has been seen to leave
//  standing with no subviews — after a sheet, a keyboard, a background pass,
//  and on the maintainer's iPhone right after the first conversation's
//  naming card left that very stack. Nothing SwiftUI owns changed, so
//  nothing was redrawn, and the person was left with the tab bar and no way
//  to type.
//
//  A `TextField(axis: .vertical)` has no host of ours to lose: SwiftUI lays
//  it out and keeps it like any other view in the pill. What upstream's text
//  view did for the chat is kept through the field's own modifiers —
//  focus through `@FocusState` bridged to AIChatView's `inputFocused`,
//  Return → Send (the preference, and a hardware keyboard), the caret for
//  the @-mention detector, the arrow keys and Tab for the `/` and `@`
//  popups on iOS 17 and later. What it cannot do is said in docs/ios.md:
//  an image pasted into the field (the plus menu offers *Paste image*
//  instead), and the select-and-replace capture for the correction learner.
//
//  Android: the composer's BasicTextField in ChatScreen.kt.
//

import GameController
import SwiftUI
import UIKit

struct NanoMuseComposerField: View {
    @Binding var text: String
    /// AIChatView's `inputFocused`: true when the chat wants the keyboard, false when it wants it gone.
    @Binding var isFocused: Bool
    var placeholder: String
    /// [T-ipad-composer-resize] The dragged height on the iPad; nil lets the text set the height.
    var fixedHeight: CGFloat?
    /// Return should send: the chat decides what that means (a popup pick, enqueue, send).
    var onReturn: () -> Void
    /// The caret as a UTF-16 offset, for the `@` detector. SwiftUI's field tells us nothing about
    /// the caret, so it is the end of the text — where it is while the person types.
    var onCaret: (Int) -> Void
    var onArrowUp: () -> Bool
    var onArrowDown: () -> Bool
    var onTab: () -> Bool
    /// True when the text is past the field's growth cap and scrolls inside it.
    var onOverflow: (Bool) -> Void

    @FocusState private var focused: Bool

    /// Upstream's growth cap: the field grows to about six lines, then scrolls.
    static let maxLines = 6

    private var font: Font { .system(size: FontSettings.shared.scaledChatInput(16.5)) }
    private var lineHeight: CGFloat { UIFont.systemFont(ofSize: FontSettings.shared.scaledChatInput(16.5)).lineHeight }

    var body: some View {
        keyHandlers(field)
            .focused($focused)
            .onAppear {
                if isFocused, !focused { focused = true }
            }
            .nmOnChange(of: isFocused) { wanted in
                if focused != wanted { focused = wanted }
            }
            .nmOnChange(of: focused) { has in
                if isFocused != has { isFocused = has }
            }
            .nmOnChange(of: text) { now in
                onCaret((now as NSString).length)
                onOverflow(Self.overflows(now, lines: lineCap))
            }
    }

    @ViewBuilder
    private var field: some View {
        let base = TextField(placeholder, text: returnAwareText, axis: .vertical)
            .font(font)
            .foregroundStyle(ChatColors.primaryText)
            .textFieldStyle(.plain)
            .submitLabel(UserDefaults.standard.integer(forKey: "returnKeyBehavior") == 1 ? .send : .return)
            .lineLimit(1...lineCap)
        if let fixedHeight {
            base.frame(height: fixedHeight, alignment: .top)
        } else {
            base
        }
    }

    /// The lines the field may grow to: six, or what the dragged height holds on the iPad.
    private var lineCap: Int {
        guard let fixedHeight, lineHeight > 0 else { return Self.maxLines }
        return max(Self.maxLines, Int(fixedHeight / lineHeight))
    }

    /// Whether the text goes past `lines`, counted the only way the field lets us: its newlines,
    /// plus a rough wrap of long lines. Only the swipe-to-send gesture reads it, to stay out of
    /// the way of the field's own scrolling.
    static func overflows(_ text: String, lines: Int, columns: Int = 32) -> Bool {
        var count = 0
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            count += max(1, (line.count + columns - 1) / columns)
            if count > lines { return true }
        }
        return false
    }

    // MARK: Return

    /// Whether a Return typed now should send: the Appearance preference ("Return → Send") on the
    /// on-screen keyboard, or a hardware keyboard, where upstream's text view sent on plain Return.
    static func returnSends(hardwareKeyboard: Bool, preference: Int) -> Bool {
        hardwareKeyboard || preference == 1
    }

    /// The text binding with Return watched: a vertical field puts a newline in on Return and
    /// never calls `onSubmit`, so a single newline appended at the end is read as the key and,
    /// when Return means send, taken back out and sent. A Chinese or Japanese keyboard confirms
    /// its composition with Return and hands the text over without a newline, so it is untouched.
    private var returnAwareText: Binding<String> {
        Binding(
            get: { text },
            set: { new in
                let sends = Self.returnSends(hardwareKeyboard: GCKeyboard.coalesced != nil, preference: UserDefaults.standard.integer(forKey: "returnKeyBehavior"))
                if sends, Self.isReturn(from: text, to: new) {
                    // Re-asserting the old value publishes it again, so the field redraws without
                    // the newline it has already shown; then the key does what Return does.
                    let old = text
                    text = old
                    onReturn()
                    return
                }
                text = new
            }
        )
    }

    /// `to` is `from` with one newline at the end: the Return key, nothing else.
    static func isReturn(from old: String, to new: String) -> Bool {
        new.hasSuffix("\n") && new.count == old.count + 1 && String(new.dropLast()) == old
    }

    // MARK: Keys

    /// iOS 17 and later: the arrow keys and Tab of a hardware keyboard walk the `/` and `@`
    /// popups, Return sends while one is open (a pick), Shift-Return is a newline. iOS 16 has
    /// no `onKeyPress`; the popups are tapped there.
    @ViewBuilder
    private func keyHandlers<V: View>(_ view: V) -> some View {
        if #available(iOS 17.0, *) {
            view
                .onKeyPress(.upArrow) { onArrowUp() ? .handled : .ignored }
                .onKeyPress(.downArrow) { onArrowDown() ? .handled : .ignored }
                .onKeyPress(.tab) { onTab() ? .handled : .ignored }
                .onKeyPress(.return, phases: .down) { press in
                    if press.modifiers.contains(.shift) { return .ignored }
                    onReturn()
                    return .handled
                }
        } else {
            view
        }
    }
}
