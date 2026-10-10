//
//  NanoMuseSignOutSheet.swift
//  Nanodot — the one question a sign-out asks (contract C12): *Keep this account's chats on
//  this device*. Off by default: the account's chats, memory, feed, goals and face leave the
//  phone with it. Used by Sign out on this phone, Sign out everywhere and Use a different server.
//

import SwiftUI

struct NanoMuseSignOutSheet: View {
    var title: String
    var message: String
    var action: String
    var onConfirm: (_ keep: Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var keep = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Section {
                    Toggle(AppLocalized("Keep this account's chats on this device"), isOn: $keep)
                } footer: {
                    Text(AppLocalized("Off: this account's chats, memory, feed, goals and face are removed from this phone. With sync on, the relay still has the chats for your next sign-in."))
                }
                Section {
                    Button(role: .destructive) {
                        dismiss()
                        onConfirm(keep)
                    } label: {
                        Text(action).frame(maxWidth: .infinity)
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
