//
//  NanoMuseRelayPicker.swift
//  Nanodot
//
//  "Use a different server": anyone can run the relay in `cloud/`, and the
//  app can sign in against it. The sheet takes an address, checks it against
//  the relay's health route (`GET /healthz`) and keeps it. https is required
//  unless the host is on one's own network (NanoMuseCloud.isPrivateHost).
//  Settings → Account shows the server in use with a Change button, which
//  signs this phone out first — a key belongs to the relay that issued it.
//  Android: the same sheet in CloudSignInScreen.
//

import SwiftUI

struct NanoMuseRelayPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @State private var checking = false
    /// The outcome of the last check: nil until checked, then the sentence and whether it passed.
    @State private var verdict: (ok: Bool, text: String)?
    /// The address the last passing check was for; a changed field needs a new check.
    @State private var checkedBase: String?

    private var normalized: String? { NanoMuseCloud.normalizedRelay(address) }
    private var usable: Bool { checkedBase != nil && checkedBase == normalized }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://relay.example.org", text: $address)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(checking)
                        .onSubmit { Task { await check() } }
                } header: {
                    Text(AppLocalized("Server address"))
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(AppLocalized("Anyone can run the Nanodot relay. https is required unless the server is on your own network."))
                        if let verdict {
                            Text(verdict.text).foregroundStyle(verdict.ok ? Color.secondary : Color.red)
                        }
                    }
                }

                Section {
                    Button {
                        Task { await check() }
                    } label: {
                        HStack {
                            Text(AppLocalized("Check"))
                            if checking { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(checking || normalized == nil)
                    Button(AppLocalized("Use this server")) {
                        guard let base = normalized, usable else { return }
                        NanoMuseCloud.setBaseURL(base)
                        dismiss()
                    }
                    .disabled(!usable || checking)
                }

                if NanoMuseCloud.usesOwnRelay {
                    Section {
                        Button(AppLocalized("Use the default server")) {
                            NanoMuseCloud.setBaseURL(nil)
                            dismiss()
                        }
                        .disabled(checking)
                    } footer: {
                        Text(String(format: AppLocalized("Server: %@"), NanoMuseCloud.relayHost))
                    }
                }
            }
            .navigationTitle(AppLocalized("Use a different server"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { dismiss() }
                }
            }
            .onAppear {
                if address.isEmpty, NanoMuseCloud.usesOwnRelay { address = NanoMuseCloud.baseURL }
            }
        }
    }

    private func check() async {
        if let problem = NanoMuseCloud.relayProblem(address) {
            verdict = (false, problem)
            checkedBase = nil
            return
        }
        guard let base = normalized else { return }
        checking = true
        defer { checking = false }
        do {
            try await NanoMuseCloud.checkRelay(base)
            verdict = (true, String(format: AppLocalized("%@ answers as a Nanodot relay."), URL(string: base)?.host ?? base))
            checkedBase = base
        } catch {
            verdict = (false, NanoMuseCloud.describe(error))
            checkedBase = nil
        }
    }
}

/// Settings → Account: the server in use and the way to change it. The page that hosts the row
/// signs out and opens the picker (`onConfirmed`): the row itself leaves the tree with the sign-out.
struct NanoMuseRelayRow: View {
    var busy: Bool
    /// The sheet's answer rides along: whether the account's chats stay on this phone (C12).
    var onConfirmed: (_ keep: Bool) -> Void
    @State private var confirming = false

    var body: some View {
        HStack {
            Text(String(format: AppLocalized("Server: %@"), NanoMuseCloud.relayHost))
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            Button(AppLocalized("Change")) { confirming = true }
                .disabled(busy)
        }
        .sheet(isPresented: $confirming) {
            NanoMuseSignOutSheet(
                title: AppLocalized("Use a different server"),
                message: AppLocalized("A sign-in belongs to the server that issued it. This phone signs out first; sign in again on the new server."),
                action: AppLocalized("Sign out and change"),
                onConfirm: onConfirmed
            )
        }
    }
}
