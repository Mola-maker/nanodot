//
//  NanoMuseRooms.swift
//  Nanodot
//
//  Two of the rooms next to the chat: Ideas (the bundled catalogue — one
//  tap sends a prompt to a new chat, schedules a routine or starts a goal)
//  and Library (the files the agent made, from every session's workspace
//  and the shared folder). Feed and Goals live in NanoMuseFeed.swift and
//  NanoMuseGoals.swift. Android: ui/ideas, ui/library.
//

import SwiftUI
import QuickLook

// MARK: - Shared bits

/// A centred empty state with a symbol, a title and a line of body text.
struct NanoMuseEmptyState: View {
    var symbol: String
    var title: String
    var message: String
    var hint: (title: String, body: String)? = nil

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.secondary)
                .padding(.bottom, 4)
            Text(title)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(message)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let hint {
                VStack(alignment: .leading, spacing: 6) {
                    Text(hint.title).font(.subheadline.weight(.semibold))
                    Text(hint.body).font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(NanoMuseTones.fill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding(.top, 10)
            }
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Ideas

struct NanoMuseIdea: Identifiable, Decodable {
    enum Kind: String, Decodable { case chat = "CHAT", routine = "ROUTINE", goal = "GOAL" }

    var id: String
    var emoji: String
    var title: String
    var body: String
    var kind: Kind
    var category: String?
    var prompt: String?
    var time: String?

    private enum CodingKeys: String, CodingKey { case id, emoji, title, body, kind, category, prompt, time }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        emoji = try c.decodeIfPresent(String.self, forKey: .emoji) ?? "💡"
        title = try c.decode(String.self, forKey: .title)
        body = try c.decodeIfPresent(String.self, forKey: .body) ?? ""
        kind = (try? c.decode(Kind.self, forKey: .kind)) ?? .chat
        category = try c.decodeIfPresent(String.self, forKey: .category)
        prompt = try c.decodeIfPresent(String.self, forKey: .prompt)
        time = try c.decodeIfPresent(String.self, forKey: .time)
    }

    var promptText: String { (prompt?.isEmpty == false ? prompt : nil) ?? title }
}

struct NanoMuseIdeaSection: Identifiable, Decodable {
    var id: String
    var title: String
    var ideas: [NanoMuseIdea]
}

enum NanoMuseIdeas {
    private struct File: Decodable { var sections: [NanoMuseIdeaSection] }

    static func load() -> [NanoMuseIdeaSection] {
        let name = NanoMuseLocale.isChinese ? "ideas.zh" : "ideas.en"
        guard let url = Bundle.main.url(forResource: name, withExtension: "json")
                ?? Bundle.main.url(forResource: "ideas.en", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(File.self, from: data) else { return [] }
        return file.sections
    }
}

struct NanoMuseIdeasRoom: View {
    var chrome: NanoMuseRoomChrome
    var onSend: (String) -> Void
    /// A ROUTINE idea: schedule it (NanoMuseScheduler) and open the editor.
    var onCreateRoutine: (NanoMuseIdea) -> Void
    /// A GOAL idea: "Create goal › category" in the main chat, with the idea's prompt as the seed.
    var onStartGoal: (NanoMuseGoalCategory, String?) -> Void

    @State private var sections: [NanoMuseIdeaSection] = []
    @State private var selected: NanoMuseIdea?

    var body: some View {
        VStack(spacing: 0) {
            // Android: the Ideas room's ••• menu has only the shared rows.
            NanoMuseRoomHeader(chrome: chrome) {
                NanoMuseRoomMenu(chrome: chrome) { EmptyView() }
            }
            if sections.isEmpty {
                NanoMuseEmptyState(symbol: "lightbulb", title: AppLocalized("Ideas"),
                                   message: AppLocalized("The ideas could not be loaded."))
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0, pinnedViews: []) {
                        ForEach(sections) { section in
                            Text(section.title)
                                .font(.headline)
                                .padding(.horizontal, 20)
                                .padding(.top, 18)
                                .padding(.bottom, 4)
                            ForEach(section.ideas) { idea in
                                Button { selected = idea } label: { row(idea) }
                                    .buttonStyle(.plain)
                            }
                        }
                        Spacer(minLength: 24)
                    }
                }
            }
        }
        .background(NanoMuseTones.canvas.ignoresSafeArea())
        .onAppear { if sections.isEmpty { sections = NanoMuseIdeas.load() } }
        .sheet(item: $selected) { idea in
            NanoMuseIdeaSheet(idea: idea) { action in
                selected = nil
                switch action {
                case .chat: onSend(idea.promptText)
                case .routine: onCreateRoutine(idea)
                case .goal: onStartGoal(NanoMuseGoalCategory.from(idea.category), idea.prompt)
                }
            }
            .presentationDetents([.medium, .large])
        }
    }

    private func row(_ idea: NanoMuseIdea) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Text(idea.emoji)
                .font(.system(size: 28))
                .frame(width: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(idea.title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                Text(idea.body)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .contentShape(Rectangle())
    }
}

private struct NanoMuseIdeaSheet: View {
    var idea: NanoMuseIdea
    var onAct: (NanoMuseIdea.Kind) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                Text(idea.emoji).font(.system(size: 40))
                Text(idea.title).font(.title3.weight(.bold))
            }
            Text(idea.body).font(.body)
            HStack(spacing: 6) {
                Image(systemName: kindSymbol).font(.footnote)
                Text(kindText).font(.footnote)
            }
            .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Button {
                onAct(idea.kind)
            } label: {
                Label(actionText, systemImage: kindSymbol)
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(NanoMuseTones.action).foregroundStyle(NanoMuseTones.onAction)
            if idea.kind != .chat {
                Button {
                    onAct(.chat)
                } label: {
                    Text(AppLocalized("Send to chat"))
                        .font(.subheadline.weight(.medium))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            if idea.kind == .routine {
                Text(AppLocalized("Runs when the app is open; at the set time the phone reminds you to open it."))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(22)
    }

    private var actionText: String {
        switch idea.kind {
        case .chat: return AppLocalized("Send to chat")
        case .routine: return AppLocalized("Create routine")
        case .goal: return AppLocalized("Start goal")
        }
    }

    private var kindSymbol: String {
        switch idea.kind {
        case .chat: return "bubble.left"
        case .routine: return "clock"
        case .goal: return "flag"
        }
    }

    private var kindText: String {
        let base: String
        switch idea.kind {
        case .chat: base = AppLocalized("Starts a conversation")
        case .routine: base = AppLocalized("Creates a routine")
        case .goal: base = AppLocalized("Creates a goal")
        }
        if idea.kind == .routine, let time = idea.time, !time.isEmpty { return "\(base) · \(time)" }
        return base
    }
}

// MARK: - Library

struct NanoMuseLibraryEntry: Identifiable, Hashable {
    enum Kind { case artifact, media }

    var id: String { url.path }
    var url: URL
    var name: String
    var relativePath: String
    var size: Int64
    var modified: Date
    var kind: Kind
    var sessionId: String?
    var sessionTitle: String?

    var symbol: String {
        switch url.pathExtension.lowercased() {
        case "png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "svg": return "photo"
        case "mp4", "mov", "avi", "mkv", "webm": return "film"
        case "mp3", "wav", "aac", "flac", "ogg", "m4a": return "waveform"
        case "pdf": return "doc.richtext"
        case "md", "txt": return "doc.text"
        case "csv", "xlsx", "xls": return "tablecells"
        case "html", "htm": return "globe"
        case "py", "js", "ts", "swift", "sh", "json": return "chevron.left.forwardslash.chevron.right"
        default: return "doc"
        }
    }
}

enum NanoMuseLibraryIndex {
    static let maxDepth = 4
    static let maxEntries = 600
    static let skipDirs: Set<String> = ["node_modules", ".git", "__pycache__", ".cache", ".venv", "venv", ".npm"]
    static let mediaExt: Set<String> = [
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "svg",
        "mp4", "mov", "avi", "mkv", "webm", "mp3", "wav", "aac", "flac", "ogg", "m4a",
    ]

    /// Every file under each session's workspace and under the shared
    /// folder, newest first.
    static func scan(titles: [String: String]) -> [NanoMuseLibraryEntry] {
        var out: [NanoMuseLibraryEntry] = []
        let fm = FileManager.default
        let base = AIChatViewModel.minisPersistentBase
        if let sessions = try? fm.contentsOfDirectory(at: base, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) {
            for dir in sessions {
                let ws = dir.appendingPathComponent("workspace", isDirectory: true)
                var isDir: ObjCBool = false
                guard fm.fileExists(atPath: ws.path, isDirectory: &isDir), isDir.boolValue else { continue }
                let sid = dir.lastPathComponent
                walk(ws, root: ws, depth: 0, sessionId: sid, title: titles[sid], into: &out)
                if out.count >= maxEntries { break }
            }
        }
        let shared = AIChatViewModel.minisSharedPersistentDir
        if out.count < maxEntries, fm.fileExists(atPath: shared.path) {
            walk(shared, root: shared, depth: 0, sessionId: nil, title: nil, into: &out)
        }
        return out.sorted { $0.modified > $1.modified }
    }

    private static func walk(_ dir: URL, root: URL, depth: Int, sessionId: String?, title: String?, into out: inout [NanoMuseLibraryEntry]) {
        guard depth <= maxDepth, out.count < maxEntries else { return }
        let fm = FileManager.default
        guard let children = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey], options: [.skipsHiddenFiles]) else { return }
        for child in children {
            guard out.count < maxEntries else { return }
            let values = try? child.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
            if values?.isDirectory == true {
                if skipDirs.contains(child.lastPathComponent) { continue }
                walk(child, root: root, depth: depth + 1, sessionId: sessionId, title: title, into: &out)
                continue
            }
            let rel = child.path.hasPrefix(root.path) ? String(child.path.dropFirst(root.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/")) : child.lastPathComponent
            let kind: NanoMuseLibraryEntry.Kind = mediaExt.contains(child.pathExtension.lowercased()) ? .media : .artifact
            out.append(NanoMuseLibraryEntry(
                url: child,
                name: child.lastPathComponent,
                relativePath: rel,
                size: Int64(values?.fileSize ?? 0),
                modified: values?.contentModificationDate ?? .distantPast,
                kind: kind,
                sessionId: sessionId,
                sessionTitle: title
            ))
        }
    }
}

struct NanoMuseLibraryRoom: View {
    var chrome: NanoMuseRoomChrome
    var sessionId: String?

    @State private var entries: [NanoMuseLibraryEntry] = []
    @State private var segment = 0
    @State private var loading = false
    @State private var preview: NanoMuseLibraryEntry?
    @State private var shareItem: NanoMuseShareTarget?

    private var shown: [NanoMuseLibraryEntry] {
        entries.filter { segment == 0 ? $0.kind == .artifact : $0.kind == .media }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Android: the Library's ••• menu — shared folders, the chat's files, then the shared rows.
            NanoMuseRoomHeader(chrome: chrome) {
                HStack(spacing: 8) {
                    NanoMuseRoundButton(symbol: "arrow.clockwise", label: AppLocalized("Refresh")) { refresh() }
                    NanoMuseRoomMenu(chrome: chrome) {
                        Button { chrome.open("sharedFolders") } label: { Label(AppLocalized("Shared folders"), systemImage: "folder.badge.person.crop") }
                        if chrome.hasMainSession {
                            Button { chrome.open("chatFiles") } label: { Label(AppLocalized("Browse Chat Files"), systemImage: "folder") }
                        }
                    }
                }
            }
            Picker("", selection: $segment) {
                Text(AppLocalized("Artifacts")).tag(0)
                Text(AppLocalized("Media")).tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if shown.isEmpty {
                if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if segment == 0 {
                    NanoMuseEmptyState(symbol: "doc.text", title: AppLocalized("Nothing created yet"),
                                       message: AppLocalized("Documents, tables and pages I write for you will show up here."))
                } else {
                    NanoMuseEmptyState(symbol: "photo.on.rectangle", title: AppLocalized("No media yet"),
                                       message: AppLocalized("Photos, screenshots and recordings I produce will show up here."))
                }
            } else {
                List {
                    ForEach(shown) { entry in
                        Button { preview = entry } label: { row(entry) }
                            .listRowBackground(NanoMuseTones.surface)
                            .contextMenu {
                                Button {
                                    shareItem = NanoMuseShareTarget(url: entry.url)
                                } label: {
                                    Label(AppLocalized("Share"), systemImage: "square.and.arrow.up")
                                }
                                if let sid = entry.sessionId {
                                    Button {
                                        NotificationCenter.default.post(name: .nanoMuseOpenChat, object: sid)
                                    } label: {
                                        Label(AppLocalized("Open conversation"), systemImage: "bubble.left")
                                    }
                                }
                            }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
        }
        .background(NanoMuseTones.canvas.ignoresSafeArea())
        .onAppear { if entries.isEmpty { refresh() } }
        .sheet(item: $preview) { entry in
            NanoMuseQuickLook(url: entry.url)
                .ignoresSafeArea()
        }
        .sheet(item: $shareItem) { target in
            NanoMuseShareSheet(items: [target.url])
        }
    }

    private func row(_ entry: NanoMuseLibraryEntry) -> some View {
        HStack(spacing: 12) {
            Image(systemName: entry.symbol)
                .font(.title3)
                .foregroundStyle(.secondary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).font(.body).foregroundStyle(.primary).lineLimit(1)
                Text(subtitle(entry)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    private func subtitle(_ entry: NanoMuseLibraryEntry) -> String {
        let size = ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file)
        let origin = entry.sessionId == nil
            ? AppLocalized("Shared folder")
            : ((entry.sessionTitle?.isEmpty == false ? entry.sessionTitle : nil) ?? AppLocalized("Untitled chat"))
        return "\(origin) · \(size) · \(NanoMuseDrawer.when(entry.modified))"
    }

    private func refresh() {
        loading = true
        Task {
            let sessions = NanoMuseSync.shared.visible(await ChatStore.shared.listSessions()) // C12: this account's chats only
            var titles: [String: String] = [:]
            for s in sessions { titles[s.id] = s.title ?? "" }
            let found = await Task.detached(priority: .utility) { NanoMuseLibraryIndex.scan(titles: titles) }.value
            await MainActor.run {
                entries = found
                loading = false
            }
        }
    }
}

struct NanoMuseShareTarget: Identifiable {
    var url: URL
    var id: String { url.absoluteString }
}

struct NanoMuseQuickLook: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

struct NanoMuseShareSheet: UIViewControllerRepresentable {
    var items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
