//
//  NanoMuseComposer.swift
//  Nanodot
//
//  Muse's composer pill, used by AIChatView when the Muse shell is on: one
//  row on a flat grey capsule — plus on the left, the field in the middle,
//  the mic on the right while there is nothing to send, the send arrow once
//  there is, the stop glyph while the agent answers. Bare glyphs, no discs.
//  Android: the `nmPill` branch of ChatScreen.kt.
//

import SwiftUI
import UIKit

/// The pill's row. AIChatView hands it the text field it already builds and
/// the actions its own buttons run, so the behaviour is upstream's and only
/// the look is Muse's.
struct NanoMuseComposerPill<Field: View>: View {
    /// Text or an attachment is in the composer.
    var hasContent: Bool
    var isProcessing: Bool
    var canSend: Bool
    var canEnqueue: Bool
    var onCamera: () -> Void
    var onPhotos: () -> Void
    var onFile: () -> Void
    /// An image on the pasteboard, as an attachment. The pill's field is SwiftUI's (0.1.38) and
    /// does not take an image paste the way upstream's text view did; the plus menu offers it
    /// whenever the pasteboard holds one.
    var onPasteImage: (UIImage) -> Void
    /// The slash commands: the "/" button has no seat in the pill, so they open from the plus menu.
    var onCommands: () -> Void
    var onMic: () -> Void
    var onSend: () -> Void
    var onEnqueue: () -> Void
    var onStop: () -> Void
    @ViewBuilder var field: () -> Field

    @State private var showAttachMenu = false

    private let glyph: CGFloat = 40

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            plusButton
            field()
                .padding(.vertical, 4)
            trailingButton
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
    }

    // MARK: Plus

    /// iOS 16's Menu misaligns inside an HStack with the keyboard up (upstream's note), so the
    /// sheet form is used there and the Menu from iOS 17 on.
    @ViewBuilder
    private var plusButton: some View {
        let icon = Image(systemName: "plus")
            .font(.system(size: 22, weight: .regular))
            .foregroundStyle(Color.primary)
            .frame(width: glyph, height: glyph)
            .contentShape(Circle())
            .accessibilityLabel(Text("Add attachment", comment: "VoiceOver label for the attachment button"))
        if #available(iOS 17, *) {
            Menu {
                attachItems
            } label: {
                icon
            }
        } else {
            Button { showAttachMenu = true } label: { icon }
                .buttonStyle(.plain)
                .confirmationDialog("Add Attachment", isPresented: $showAttachMenu) {
                    attachItems
                }
        }
    }

    @ViewBuilder
    private var attachItems: some View {
        Button(action: onCamera) { Label("Take Photo", systemImage: "camera") }
        Button(action: onPhotos) { Label("Choose Photos & Videos", systemImage: "photo.on.rectangle") }
        Button(action: onFile) { Label("Add File", systemImage: "doc") }
        // `hasImages` reads only the pasteboard's types, so no paste banner; the image itself is
        // read when the person asks for it.
        if UIPasteboard.general.hasImages {
            Button {
                if let image = UIPasteboard.general.image { onPasteImage(image) }
            } label: {
                Label(AppLocalized("Paste image"), systemImage: "doc.on.clipboard")
            }
        }
        Button(action: onCommands) { Label(AppLocalized("Commands"), systemImage: "terminal") }
    }

    // MARK: Mic · send · stop

    @ViewBuilder
    private var trailingButton: some View {
        if isProcessing && canEnqueue {
            Button(action: onEnqueue) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.primary)
                    .frame(width: glyph, height: glyph)
            }
            .buttonStyle(.plain)
            .keyboardShortcut(.return, modifiers: .command)
            .accessibilityLabel(Text("Add to queue", comment: "VoiceOver label for the send button while a reply is generating"))
        } else if isProcessing {
            Button(action: onStop) {
                Image(systemName: "stop.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.primary)
                    .frame(width: glyph, height: glyph)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Stop generating", comment: "VoiceOver label for the stop button"))
        } else if hasContent {
            Button(action: onSend) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(canSend ? Color.primary : Color.secondary.opacity(0.5))
                    .frame(width: glyph, height: glyph)
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
            .keyboardShortcut(.return, modifiers: .command)
            .accessibilityLabel(Text("Send", comment: "VoiceOver label for the send button"))
        } else {
            Button(action: onMic) {
                Image(systemName: "mic")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(Color.primary)
                    .frame(width: glyph, height: glyph)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Voice input", comment: "Mic button opens voice panel"))
        }
    }
}
