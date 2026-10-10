//
//  NanoMuseFromDeviceCaption.swift
//  Nanodot
//
//  C8: a text that came down through account sync is shown as an ordinary
//  bubble with one small line under it — "From Pixel 8" — nothing else marks
//  it. The chat list carries no such badge; the caption is the whole story.
//

import SwiftUI

/// The "From {device}" line under a synced bubble, and — C9 — "{device} is working…" under the
/// last remote user line while that device's turn runs. Renders nothing for this phone's own rows.
struct NanoMuseFromDeviceCaption: View {
    /// `ChatMessage.nmFromDevice`: the other device's name, or nil.
    let device: String?
    /// `ChatMessage.nmWorkingDevice`: the device at work on the conversation, or nil.
    let working: String?

    init(_ device: String?, working: String? = nil) {
        self.device = device
        self.working = working
    }

    var body: some View {
        if let device, !device.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(String(format: AppLocalized("From %@"), device))
                    .accessibilityLabel(String(format: AppLocalized("From %@"), device))
                if let working, !working.isEmpty {
                    Text(String(format: AppLocalized("%@ is working…"), working))
                        .accessibilityLabel(String(format: AppLocalized("%@ is working…"), working))
                }
            }
            .font(.system(size: FontSettings.shared.scaledMessage(11.5)))
            .foregroundStyle(ChatColors.secondaryText)
            .padding(.horizontal, 4)
        }
    }
}
