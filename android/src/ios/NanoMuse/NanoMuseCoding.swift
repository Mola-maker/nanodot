//
//  NanoMuseCoding.swift
//  Nanodot
//
//  The coding agents on another computer of the account, over the hub:
//  the `coding.*` actions the runtime there answers (docs/coding-agents.md).
//  Their sessions, their last reply, and a message to any of them — from
//  the phone. Nothing of theirs is copied here.
//  Android: coding/CodingBridge.kt, ui/coding/CodingScreen.kt.
//

import SwiftUI

// MARK: - Bridge

enum NanoMuseCodingBridge {
    /// A coding agent as the computer reports it.
    struct Agent: Identifiable, Equatable {
        var id: String
        var name: String
        var installed: Bool
        var version: String
        var running: Int
    }

    /// One of the agent's sessions on that computer; `id` is empty for one not started yet.
    struct Session: Identifiable, Equatable {
        var agent: String
        var id: String
        var title: String
        var workspace: String
        var updatedAt: Date?
        var messages: Int
        var status: String
        var lastUser: String
        var lastAssistant: String
        var resumable: Bool

        /// A list key that survives an empty id (a fresh session).
        var key: String { agent + "|" + (id.isEmpty ? workspace : id) }
    }

    struct Message: Identifiable, Equatable {
        var id = UUID()
        var role: String
        var text: String
    }

    /// A message in flight: what was sent and what has come back so far.
    struct LiveRun: Equatable {
        var text: String
        var id = ""
        var sessionId = ""
        var status = "running"
        /// Finished paragraphs.
        var output = ""
        /// The paragraph being written (Cursor streams deltas, then sends the whole message).
        var current = ""
        var lastTool = ""
        var tools = 0
        var error = ""
    }

    static let listTimeout: TimeInterval = 30
    static let sendTimeout: TimeInterval = 30 * 60

    @MainActor
    static func agents(device: String) async throws -> [Agent] {
        let reply = try await NanoMuseHub.shared.call(to: device, action: "coding.agents", args: [:], timeout: listTimeout)
        return ((reply["agents"] as? [[String: Any]]) ?? []).map { o in
            let id = o["id"] as? String ?? ""
            let name = (o["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id
            return Agent(id: id, name: name, installed: (o["installed"] as? Bool) ?? false, version: o["version"] as? String ?? "", running: (o["running"] as? NSNumber)?.intValue ?? 0)
        }
    }

    @MainActor
    static func sessions(device: String, agent: String?, limit: Int = 40) async throws -> [Session] {
        var args: [String: Any] = ["limit": limit]
        if let agent, !agent.isEmpty { args["agent"] = agent }
        let reply = try await NanoMuseHub.shared.call(to: device, action: "coding.sessions", args: args, timeout: listTimeout)
        return ((reply["sessions"] as? [[String: Any]]) ?? []).map(session)
    }

    static func session(_ o: [String: Any]) -> Session {
        let updated = (o["updated_at"] as? NSNumber)?.doubleValue ?? 0
        return Session(
            agent: o["agent"] as? String ?? "",
            id: o["id"] as? String ?? "",
            title: o["title"] as? String ?? "",
            workspace: o["workspace"] as? String ?? "",
            updatedAt: updated > 0 ? Date(timeIntervalSince1970: updated) : nil,
            messages: (o["messages"] as? NSNumber)?.intValue ?? 0,
            status: (o["status"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "idle",
            lastUser: o["last_user"] as? String ?? "",
            lastAssistant: o["last_assistant"] as? String ?? "",
            resumable: (o["resumable"] as? Bool) ?? true
        )
    }

    @MainActor
    static func transcript(device: String, agent: String, sessionId: String) async throws -> [Message] {
        let reply = try await NanoMuseHub.shared.call(to: device, action: "coding.session", args: ["agent": agent, "session_id": sessionId], timeout: listTimeout)
        return ((reply["transcript"] as? [[String: Any]]) ?? [])
            .map { Message(role: $0["role"] as? String ?? "", text: $0["text"] as? String ?? "") }
            .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    /// Send `text` to the agent and follow the run; `onUpdate` gets every step, the result is the final state.
    @MainActor
    static func send(device: String, agent: String, text: String, sessionId: String, workspace: String, onUpdate: @escaping (LiveRun) -> Void) async throws -> LiveRun {
        var run = LiveRun(text: text, sessionId: sessionId)
        let args: [String: Any] = ["agent": agent, "text": text, "session_id": sessionId, "workspace": workspace, "wait": true]
        let final = try await NanoMuseHub.shared.call(to: device, action: "coding.send", args: args, timeout: sendTimeout) { ev in
            run = step(run, event: ev)
            onUpdate(run)
        }
        return finish(run, reply: final)
    }

    /// One `coding.send` event folded into the run (pure, tested).
    static func step(_ run: LiveRun, event ev: [String: Any]) -> LiveRun {
        var r = run
        switch ev["kind"] as? String ?? "" {
        case "started":
            r.id = ev["run"] as? String ?? r.id
            if let sid = ev["session_id"] as? String, !sid.isEmpty { r.sessionId = sid }
        case "text":
            let t = ev["text"] as? String ?? ""
            if (ev["partial"] as? Bool) == true {
                r.current += t
            } else {
                r.output = [r.output, t].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: "\n")
                r.current = ""
            }
        case "tool":
            r.tools += 1
            r.lastTool = ev["text"] as? String ?? ""
        default:
            break
        }
        return r
    }

    /// The final reply folded into the run (pure, tested).
    static func finish(_ run: LiveRun, reply final: [String: Any]) -> LiveRun {
        var r = run
        r.id = final["id"] as? String ?? run.id
        if let sid = final["session_id"] as? String, !sid.isEmpty { r.sessionId = sid }
        r.status = (final["status"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "done"
        let output = final["output"] as? String ?? ""
        r.output = output.trimmingCharacters(in: .whitespaces).isEmpty
            ? [run.output, run.current].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: "\n")
            : output
        r.current = ""
        r.tools = (final["tools"] as? NSNumber)?.intValue ?? run.tools
        r.error = final["error"] as? String ?? ""
        return r
    }

    @MainActor
    static func stop(device: String, runId: String) async throws -> Bool {
        let reply = try await NanoMuseHub.shared.call(to: device, action: "coding.stop", args: ["run": runId], timeout: listTimeout)
        return (reply["stopped"] as? Bool) ?? false
    }
}

// MARK: - Screen

struct NanoMuseCodingView: View {
    @ObservedObject private var hub = NanoMuseHub.shared
    @State private var chosenId: String?
    @State private var agents: [NanoMuseCodingBridge.Agent] = []
    @State private var sessions: [NanoMuseCodingBridge.Session] = []
    @State private var filter: String?
    @State private var loading = false
    @State private var error: String?
    @State private var open: NanoMuseCodingBridge.Session?
    @State private var newWith: NanoMuseCodingBridge.Agent?

    private var computers: [HubDevice] {
        hub.others.filter { $0.online && $0.isComputer && ($0.actions.isEmpty || $0.actions.contains("coding.sessions")) }
    }
    private var computer: HubDevice? { computers.first { $0.id == chosenId } ?? computers.first }

    var body: some View {
        List {
            Section {
                Text(AppLocalized("Cursor, Codex and Claude Code on your computers: their sessions, their last reply, and a message to any of them, from here. Nothing of theirs is copied to the phone."))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if computer == nil {
                    Text(AppLocalized("No computer with a coding agent is online. Run Nanodot on the computer where Cursor, Codex or Claude Code is installed, signed in with this account."))
                        .font(.subheadline)
                    if let hint = NanoMuseCloud.account?.hint, !hint.isEmpty {
                        Text(String(format: AppLocalized("This phone is signed in as %@; the computer must use the same account."), hint))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                NavigationLink {
                    Form {
                        NanoMuseDevicesSection()
                        NanoMuseReachSection()
                    }
                    .navigationTitle(AppLocalized("Devices"))
                    .navigationBarTitleDisplayMode(.inline)
                } label: {
                    Label(AppLocalized("Devices"), systemImage: "desktopcomputer")
                }
            }
            if let computer {
                if computers.count > 1 {
                    Section {
                        Picker(AppLocalized("Computer"), selection: Binding(get: { computer.id }, set: { chosenId = $0 })) {
                            ForEach(computers) { c in Text(c.name).tag(c.id) }
                        }
                    }
                }
                if !agents.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                chip(AppLocalized("All"), selected: filter == nil, running: 0, installed: true) { filter = nil }
                                ForEach(agents) { a in
                                    chip(a.name, selected: filter == a.id, running: a.running, installed: a.installed) { filter = a.id }
                                }
                            }
                            .padding(.vertical, 2)
                        }
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    }
                    Section(AppLocalized("Start something new")) {
                        ForEach(agents.filter { $0.installed && (filter == nil || filter == $0.id) }) { a in
                            Button {
                                newWith = a
                            } label: {
                                Label(String(format: AppLocalized("New conversation with %@"), a.name), systemImage: "plus.bubble")
                            }
                        }
                    }
                }
                Section {
                    let shown = sessions.filter { filter == nil || $0.agent == filter }
                    if shown.isEmpty {
                        Text(loading ? AppLocalized("Refreshing…") : AppLocalized("No sessions on this computer yet."))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(shown, id: \.key) { s in
                        Button {
                            open = s
                        } label: {
                            sessionRow(s)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text(AppLocalized("Recent sessions"))
                } footer: {
                    if let error { Text(error).foregroundStyle(.red) }
                }
            }
        }
        .navigationTitle(AppLocalized("Coding agents"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    load()
                } label: {
                    if loading { ProgressView() } else { Image(systemName: "arrow.clockwise") }
                }
                .disabled(loading || computer == nil)
                .accessibilityLabel(AppLocalized("Refresh"))
            }
        }
        .task(id: computer?.id) {
            if computer != nil { load() } else { agents = []; sessions = [] }
        }
        .sheet(item: $open, onDismiss: { load() }) { s in
            if let computer {
                NavigationStack {
                    NanoMuseCodingSessionView(computer: computer, session: s, agentName: agents.first { $0.id == s.agent }?.name ?? s.agent)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button(AppLocalized("Done")) { open = nil } }
                        }
                }
            }
        }
        .sheet(item: $newWith) { a in
            NanoMuseCodingNewSheet(agent: a, workspaces: Array(Set(sessions.filter { $0.agent == a.id }.map(\.workspace).filter { !$0.isEmpty })).sorted()) { workspace in
                newWith = nil
                open = NanoMuseCodingBridge.Session(agent: a.id, id: "", title: "", workspace: workspace, updatedAt: nil, messages: 0, status: "idle", lastUser: "", lastAssistant: "", resumable: true)
            }
            .presentationDetents([.medium])
        }
    }

    private func load() {
        guard let dev = computer, !loading else { return }
        loading = true
        error = nil
        Task { @MainActor in
            do {
                agents = try await NanoMuseCodingBridge.agents(device: dev.id)
                sessions = try await NanoMuseCodingBridge.sessions(device: dev.id, agent: nil)
            } catch {
                self.error = error.localizedDescription
            }
            loading = false
        }
    }

    private func chip(_ label: String, selected: Bool, running: Int, installed: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(label).font(.subheadline.weight(.medium))
                if running > 0 {
                    Text("\(running)").font(.caption2.weight(.semibold))
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(NanoMuseTones.action.opacity(0.18), in: Capsule())
                }
            }
            .foregroundStyle(installed ? Color.primary : Color.secondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(selected ? NanoMuseTones.action.opacity(0.15) : NanoMuseTones.fill, in: Capsule())
            .overlay(Capsule().stroke(selected ? NanoMuseTones.action : Color.clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func sessionRow(_ s: NanoMuseCodingBridge.Session) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(s.title.isEmpty ? (s.lastUser.isEmpty ? AppLocalized("Untitled") : s.lastUser) : s.title)
                    .font(.body.weight(.medium)).lineLimit(1)
                Spacer()
                Text(s.status == "running" ? AppLocalized("running") : (s.updatedAt.map { NanoMuseDrawer.when($0) } ?? ""))
                    .font(.caption).foregroundStyle(s.status == "running" ? NanoMuseTones.action : Color.secondary)
            }
            HStack(spacing: 6) {
                Text(agents.first { $0.id == s.agent }?.name ?? s.agent).font(.caption.weight(.medium)).foregroundStyle(.secondary)
                if !s.workspace.isEmpty {
                    Text((s.workspace as NSString).lastPathComponent).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            if !s.lastAssistant.isEmpty {
                Text(s.lastAssistant).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .contentShape(Rectangle())
    }
}

/// "New conversation with Cursor": the project folder on the computer, then Start.
struct NanoMuseCodingNewSheet: View {
    var agent: NanoMuseCodingBridge.Agent
    var workspaces: [String]
    var onStart: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var workspace = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(AppLocalized("Project folder on the computer"), text: $workspace)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if !workspaces.isEmpty {
                        ForEach(workspaces, id: \.self) { w in
                            Button(w) { workspace = w }.font(.footnote).lineLimit(1)
                        }
                    }
                } footer: {
                    Text(AppLocalized("The agent works inside this folder. Leave it empty for the computer's default."))
                }
            }
            .navigationTitle(String(format: AppLocalized("New conversation with %@"), agent.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(AppLocalized("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(AppLocalized("Start")) { onStart(workspace.trimmingCharacters(in: .whitespaces)) } }
            }
        }
    }
}

/// One session: the transcript so far, the run in flight, a composer.
struct NanoMuseCodingSessionView: View {
    var computer: HubDevice
    var session: NanoMuseCodingBridge.Session
    var agentName: String

    @State private var sessionId: String
    @State private var transcript: [NanoMuseCodingBridge.Message] = []
    @State private var live: NanoMuseCodingBridge.LiveRun?
    @State private var draft = ""
    @State private var loading = false
    @State private var error: String?

    init(computer: HubDevice, session: NanoMuseCodingBridge.Session, agentName: String) {
        self.computer = computer
        self.session = session
        self.agentName = agentName
        _sessionId = State(initialValue: session.id)
    }

    private var continuesAsNew: Bool { !session.id.isEmpty && !session.resumable }
    private var liveLength: Int { (live?.output.count ?? 0) + (live?.current.count ?? 0) }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if transcript.isEmpty && live == nil {
                            Text(loading ? AppLocalized("Refreshing…")
                                 : sessionId.isEmpty ? String(format: AppLocalized("A new conversation with %@. Your first message starts it on the computer."), agentName)
                                 : AppLocalized("Nothing readable in this session yet."))
                                .font(.subheadline).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .center)
                                .padding(.top, 40)
                        }
                        ForEach(transcript) { m in bubble(role: m.role, text: m.text) }
                        if let live {
                            bubble(role: "user", text: live.text)
                            liveBlock(live).id("live")
                        }
                        if let error {
                            Text(error).font(.footnote).foregroundStyle(.red).padding(.horizontal, 14)
                        }
                    }
                    .padding(.vertical, 12)
                }
                .nmOnChange(of: transcript.count) { _ in
                    if let last = transcript.last?.id { proxy.scrollTo(last, anchor: .bottom) }
                }
                .nmOnChange(of: liveLength) { _ in proxy.scrollTo("live", anchor: .bottom) }
            }
            Divider()
            HStack(alignment: .bottom, spacing: 8) {
                TextField(continuesAsNew ? AppLocalized("Made in the IDE: continues as a new chat in the same workspace…") : String(format: AppLocalized("Message %@…"), agentName), text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.roundedBorder)
                if live?.status == "running" {
                    Button {
                        Task { await stop() }
                    } label: {
                        Image(systemName: "stop.circle.fill").font(.system(size: 28))
                    }
                    .accessibilityLabel(AppLocalized("Stop"))
                } else {
                    Button {
                        send()
                    } label: {
                        Image(systemName: "arrow.up.circle.fill").font(.system(size: 28))
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel(AppLocalized("Send"))
                }
            }
            .padding(10)
        }
        .background(NanoMuseTones.canvas.ignoresSafeArea())
        .navigationTitle(session.title.isEmpty ? String(format: AppLocalized("New conversation with %@"), agentName) : session.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { reload() } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(loading)
                    .accessibilityLabel(AppLocalized("Refresh"))
            }
        }
        .task(id: sessionId) { reload() }
    }

    private func reload() {
        guard !sessionId.isEmpty else { return }
        loading = true
        Task { @MainActor in
            do {
                transcript = try await NanoMuseCodingBridge.transcript(device: computer.id, agent: session.agent, sessionId: sessionId)
                error = nil
            } catch {
                self.error = error.localizedDescription
            }
            loading = false
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, live?.status != "running" else { return }
        draft = ""
        error = nil
        live = NanoMuseCodingBridge.LiveRun(text: text)
        Task { @MainActor in
            do {
                let final = try await NanoMuseCodingBridge.send(device: computer.id, agent: session.agent, text: text, sessionId: sessionId, workspace: session.workspace) { updated in
                    Task { @MainActor in live = updated }
                }
                live = final
                if !final.sessionId.isEmpty, final.sessionId != sessionId { sessionId = final.sessionId } else { reload() }
            } catch {
                live?.status = "failed"
                live?.error = error.localizedDescription
            }
        }
    }

    private func stop() async {
        guard let id = live?.id, !id.isEmpty else { return }
        _ = try? await NanoMuseCodingBridge.stop(device: computer.id, runId: id)
    }

    private func bubble(role: String, text: String) -> some View {
        let user = role == "user"
        return HStack {
            if user { Spacer(minLength: 40) }
            Text(text)
                .font(.callout)
                .textSelection(.enabled)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(user ? NanoMuseTones.action.opacity(0.15) : NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            if !user { Spacer(minLength: 40) }
        }
        .padding(.horizontal, 12)
    }

    private func liveBlock(_ run: NanoMuseCodingBridge.LiveRun) -> some View {
        let body = [run.output, run.current].filter { !$0.isEmpty }.joined(separator: "\n")
        let status: String? = {
            switch run.status {
            case "running": return body.isEmpty && run.lastTool.isEmpty ? AppLocalized("Working…") : nil
            case "failed": return String(format: AppLocalized("Failed: %@"), run.error.isEmpty ? "?" : run.error)
            case "stopped": return AppLocalized("Stopped.")
            default: return run.tools > 0 ? String(format: AppLocalized("%d tool calls"), run.tools) : nil
            }
        }()
        return HStack {
            VStack(alignment: .leading, spacing: 6) {
                if !body.isEmpty { Text(body).font(.callout).textSelection(.enabled) }
                if run.status == "running", !run.lastTool.isEmpty {
                    Label(run.lastTool, systemImage: "wrench").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                if let status {
                    HStack(spacing: 6) {
                        if run.status == "running" { ProgressView().controlSize(.small) }
                        Text(status).font(.caption).foregroundStyle(run.status == "failed" ? Color.red : Color.secondary)
                    }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            Spacer(minLength: 40)
        }
        .padding(.horizontal, 12)
    }
}
