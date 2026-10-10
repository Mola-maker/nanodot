//
//  NanoMuseSyncSettings.swift
//  Nanodot
//
//  Contract C7, rule 6: the switch and the delete action for synced
//  conversations, as a section of Data controls; C9: the per-device
//  "Also sync side chats" switch under it, default off. Android: the same
//  rows in DataControlsScreen.kt.
//

import SwiftUI

struct NanoMuseSyncSection: View {
    @ObservedObject private var sync = NanoMuseSync.shared
    @State private var on = true
    @State private var sideChats = false
    @State private var busy = false
    @State private var confirmDelete = false
    @State private var message: String?

    private var signedIn: Bool { NanoMuseCloud.isSignedIn }

    var body: some View {
        Section {
            Toggle(isOn: $on) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(AppLocalized("Sync conversations between my devices"))
                    if !signedIn {
                        Text(AppLocalized("Sign in to sync"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else if sync.conversationCount > 0 || sync.messageCount > 0 {
                        Text(String(format: AppLocalized("%d conversations, %d messages on nanoMuse Cloud."), sync.conversationCount, sync.messageCount))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .disabled(!signedIn || busy)
            .onAppear { on = sync.enabled }
            .nmOnChange(of: on) { value in
                guard signedIn, value != sync.enabled, !busy else { return }
                busy = true
                Task { @MainActor in
                    defer { busy = false }
                    await sync.setEnabled(value)
                    on = sync.enabled
                    message = sync.lastError
                }
            }
            .nmOnChange(of: sync.serverEnabled) { _ in
                on = sync.enabled
            }
            // C9: per device, default off. Only meaningful while the account's switch is on.
            Toggle(isOn: $sideChats) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(AppLocalized("Also sync side chats"))
                    Text(AppLocalized("Off: side chats stay on this device. On: this device's side chats go to the account and the other devices' side chats come here."))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(!signedIn || !on || busy)
            .onAppear { sideChats = sync.sideChats }
            .nmOnChange(of: sideChats) { value in
                guard value != sync.sideChats else { return }
                sync.setSideChats(value)
            }
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                Text(AppLocalized("The text of your chats is kept on nanoMuse Cloud so every device shows the same conversations. Files and images stay on the device they were made on."))
                if let message, !message.isEmpty {
                    Text(message)
                }
            }
        }

        if signedIn {
            Section {
                Button(role: .destructive) {
                    confirmDelete = true
                } label: {
                    Label(AppLocalized("Delete synced conversations"), systemImage: "trash")
                }
                .disabled(busy)
                .confirmationDialog(AppLocalized("Delete synced conversations"), isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button(AppLocalized("Delete"), role: .destructive) {
                        busy = true
                        Task { @MainActor in
                            defer { busy = false }
                            await sync.deleteSynced()
                            message = sync.lastError ?? AppLocalized("The synced conversations are removed from nanoMuse Cloud. The chats on your devices stay.")
                        }
                    }
                    Button(AppLocalized("Cancel"), role: .cancel) {}
                } message: {
                    Text(AppLocalized("Removes what nanoMuse Cloud holds of your conversations. Every device keeps its own chats; the switch stays as it is."))
                }
            } footer: {
                Text(AppLocalized("Deleting a chat on one device deletes it on all of them. Nobody but your devices can read the text; the operator sees counts, not words."))
            }
            .task { await sync.refreshState() }
        }
    }
}
