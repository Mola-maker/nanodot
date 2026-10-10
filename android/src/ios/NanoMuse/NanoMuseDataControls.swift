//
//  NanoMuseDataControls.swift
//  Nanodot
//
//  Data controls for a nanoMuse Cloud account: whether the text of chats
//  with the Cloud models is kept to train the community's model, how many
//  turns were kept, and a way to delete them. Android: ui/cloud/DataControlsScreen.kt.
//

import SwiftUI

struct NanoMuseDataControlsView: View {
    @State private var info: NanoMuseRelayMedia.Contribute?
    @State private var on = false
    @State private var loading = false
    @State private var busy = false
    @State private var message: String?
    @State private var confirmDelete = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        Form {
            // C7: the conversations shared between the account's devices (NanoMuseSyncSettings.swift).
            NanoMuseSyncSection()
            if !NanoMuseCloud.isSignedIn {
                Section {
                    Text(AppLocalized("Sign in to nanoMuse Cloud to use it."))
                        .foregroundStyle(.secondary)
                }
            } else {
                Section {
                    Toggle(isOn: $on) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(AppLocalized("Help improve nanoMuse's AI models"))
                            if let info {
                                Text(String(format: AppLocalized("%d turns kept so far."), info.samples))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(busy || loading || info == nil)
                    .nmOnChange(of: on) { value in
                        guard let info, value != info.on, !busy else { return }
                        set(value)
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(AppLocalized("While this is on, the text of your chats with the nanoMuse Cloud models is kept on the relay to train the community's own open model: what you wrote, what it answered and the tools it chose to call. Not your memory or SOUL (the system prompt), not what tools returned, not pictures, and never next to who you are. Your own API key never passes through the relay."))
                        if let info {
                            Text(info.defaultOn ? AppLocalized("New accounts start with it on.") : AppLocalized("New accounts start with it off."))
                        }
                    }
                }

                if let info, !info.privacyURL.isEmpty, let url = URL(string: info.privacyURL) {
                    Section {
                        Button {
                            openURL(url)
                        } label: {
                            Label(AppLocalized("Privacy policy"), systemImage: "doc.text")
                        }
                    }
                }

                Section {
                    Button(role: .destructive) {
                        confirmDelete = true
                    } label: {
                        Label(AppLocalized("Delete the kept conversations"), systemImage: "trash")
                    }
                    .disabled(busy || loading)
                } footer: {
                    Text(AppLocalized("Removes every turn kept from this account, whether the switch is on or off now. Turning the switch off keeps what was kept until you delete it here."))
                }

                if let message {
                    Section {
                        Text(message).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle(AppLocalized("Data controls"))
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if loading && info == nil {
                ProgressView()
            }
        }
        .confirmationDialog(AppLocalized("Delete the kept conversations"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(AppLocalized("Delete"), role: .destructive) { deleteSamples() }
            Button(AppLocalized("Cancel"), role: .cancel) {}
        } message: {
            Text(AppLocalized("The kept conversations are removed from the server. This cannot be undone."))
        }
        .task { await load() }
    }

    private func load() async {
        guard NanoMuseCloud.isSignedIn else { return }
        loading = true
        defer { loading = false }
        do {
            let c = try await NanoMuseRelayMedia.contribute()
            info = c
            on = c.on
        } catch {
            message = NanoMuseCloud.describe(error)
        }
    }

    private func set(_ value: Bool) {
        busy = true
        Task { @MainActor in
            defer { busy = false }
            do {
                let c = try await NanoMuseRelayMedia.setContribute(value)
                info = c
                on = c.on
                message = c.on ? AppLocalized("On. Turn it off here at any time.") : AppLocalized("Off. Nothing more is kept.")
            } catch {
                on = info?.on ?? false
                message = NanoMuseCloud.describe(error)
            }
        }
    }

    private func deleteSamples() {
        busy = true
        Task { @MainActor in
            defer { busy = false }
            do {
                let n = try await NanoMuseRelayMedia.deleteSamples()
                message = String(format: AppLocalized("%d turns deleted."), n)
                if var c = info {
                    c.samples = 0
                    info = c
                }
            } catch {
                message = NanoMuseCloud.describe(error)
            }
        }
    }
}
