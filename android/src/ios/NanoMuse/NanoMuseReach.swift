//
//  NanoMuseReach.swift
//  Nanodot
//
//  Reaching the account's other devices from this iPhone through the hub:
//  open a link there, send a note, ask for a screenshot, run a shell
//  command. A small manual surface on top of NanoMuseHub.call so the
//  outbound side works without the agent in the loop.
//

import SwiftUI

struct NanoMuseReachSection: View {
    @ObservedObject private var hub = NanoMuseHub.shared
    @State private var target: HubDevice?

    var body: some View {
        if hub.enabled, !hub.others.isEmpty {
            Section {
                ForEach(hub.others) { device in
                    Button {
                        target = device
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: device.isComputer ? "desktopcomputer" : "iphone")
                                .foregroundStyle(device.online ? Color.primary : Color.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.name).foregroundStyle(.primary)
                                Text(device.online ? AppLocalized("online") : AppLocalized("offline"))
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                        }
                    }
                    .disabled(!device.online)
                }
            } header: {
                Text(AppLocalized("Reach a device"))
            } footer: {
                Text(AppLocalized("In a chat, just ask: “on my Mac, list the downloads folder”, “tell my PC to build the project and send me the log”. Approvals show on this phone."))
            }
            .sheet(item: $target) { device in
                NanoMuseReachSheet(device: device)
            }
        }
    }
}

private struct NanoMuseReachSheet: View {
    let device: HubDevice
    @Environment(\.dismiss) private var dismiss

    @State private var link = ""
    @State private var note = ""
    @State private var command = ""
    @State private var busy = false
    @State private var result: String?
    @State private var screenshot: UIImage?

    var body: some View {
        NavigationStack {
            Form {
                // C7: the task goes to that device's Muse; the composer opens with "@<name> " in it.
                Section {
                    Button {
                        dismiss()
                        NotificationCenter.default.post(name: .nanoMuseHomeAction, object: "askDevice", userInfo: ["text": "@\(device.name) "])
                    } label: {
                        Label(AppLocalized("Ask this device"), systemImage: "bubble.left.and.text.bubble.right")
                    }
                    .disabled(!device.online)
                } footer: {
                    Text(AppLocalized("Start a message with @ and the device's name and your agent there does the work; the answer comes back to this chat."))
                }

                Section {
                    TextField("https://…", text: $link)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button(AppLocalized("Open it there")) {
                        run("open", ["url": link.trimmingCharacters(in: .whitespaces)]) { body in
                            AppLocalized("Opened.")
                        }
                    }
                    .disabled(busy || URL(string: link.trimmingCharacters(in: .whitespaces))?.scheme == nil)
                } header: {
                    Text(AppLocalized("Open a link"))
                }

                Section {
                    TextField(AppLocalized("A short note"), text: $note, axis: .vertical)
                        .lineLimit(1...4)
                    Button(AppLocalized("Send the note")) {
                        run("notify", ["text": note.trimmingCharacters(in: .whitespacesAndNewlines), "title": "Nanodot"]) { _ in
                            AppLocalized("Sent.")
                        }
                    }
                    .disabled(busy || note.trimmingCharacters(in: .whitespaces).isEmpty)
                } header: {
                    Text(AppLocalized("Send a note"))
                }

                if device.isComputer {
                    Section {
                        TextField(AppLocalized("A shell command"), text: $command, axis: .vertical)
                            .font(.system(.body, design: .monospaced))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .lineLimit(1...4)
                        Button(AppLocalized("Run it there")) {
                            run("shell", ["command": command, "timeout": 120]) { body in
                                let out = (body["stdout"] as? String ?? "") + (body["stderr"] as? String ?? "")
                                let code = body["exit_code"] as? Int ?? body["code"] as? Int ?? 0
                                return out.isEmpty ? String(format: AppLocalized("Finished with exit code %d."), code) : String(out.prefix(4000))
                            }
                        }
                        .disabled(busy || command.trimmingCharacters(in: .whitespaces).isEmpty)
                        Button(AppLocalized("Take a screenshot there")) {
                            run("screen", [:]) { body in
                                if let b64 = (body["png"] as? String) ?? (body["image"] as? String) ?? (body["data"] as? String),
                                   let data = Data(base64Encoded: b64), let image = UIImage(data: data) {
                                    screenshot = image
                                    return AppLocalized("Screenshot received.")
                                }
                                return AppLocalized("The computer sent no picture.")
                            }
                        }
                        .disabled(busy)
                    } header: {
                        Text(AppLocalized("Operate the computer"))
                    } footer: {
                        Text(AppLocalized("Only when that computer allows being operated from other devices; it asks for approval there when it is set to."))
                    }
                }

                if busy {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(AppLocalized("Asking…")).foregroundStyle(.secondary)
                        }
                    }
                }
                if let result {
                    Section {
                        Text(result)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                if let screenshot {
                    Section {
                        Image(uiImage: screenshot)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                }
            }
            .navigationTitle(device.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(AppLocalized("Done")) { dismiss() }
                }
            }
        }
    }

    private func run(_ action: String, _ args: [String: Any], _ describe: @escaping ([String: Any]) -> String) {
        busy = true
        result = nil
        Task { @MainActor in
            defer { busy = false }
            do {
                let body = try await NanoMuseHub.shared.call(to: device.id, action: action, args: args, timeout: 150)
                result = describe(body)
            } catch {
                result = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }
}
