//
//  NanoMuseSystemFiles.swift
//  Nanodot
//
//  The agent's own files, readable and (mostly) editable by the person:
//  SOUL.md, USER.md, GLOBAL.md and the diary in the memory folder (what
//  the shell sees under /var/minis/memory), feed-preferences.md, and
//  HEARTBEAT.md — a read-only rendering of the routines. Plus "Import
//  memory": paste what another assistant remembers. Android: sysfiles/.
//

import Foundation
import SwiftUI
import UIKit

enum NanoMuseSystemFile: String, CaseIterable, Identifiable {
    case soul, user, memory, feed, heartbeat
    var id: String { rawValue }

    var fileName: String {
        switch self {
        case .soul: return "SOUL.md"
        case .user: return "USER.md"
        case .memory: return "GLOBAL.md"
        case .feed: return "feed-preferences.md"
        case .heartbeat: return "HEARTBEAT.md"
        }
    }

    var editable: Bool { self != .heartbeat }

    /// Where the file lives; HEARTBEAT has no file of its own.
    var url: URL {
        switch self {
        case .soul: return SoulStore.fileURL
        case .user, .memory: return NanoMuseDirs.memory.appendingPathComponent(fileName)
        case .feed: return NanoMuseDirs.root.appendingPathComponent(fileName)
        case .heartbeat: return NanoMuseDirs.root.appendingPathComponent(fileName)
        }
    }

    var about: String {
        switch self {
        case .soul: return AppLocalized("This is the agent's persona: the values and habits it tries to hold to in every conversation, its name, and the style it answers in. It starts from a template that ships with Nanodot; the agent may refine it over time and tells you when it does. You can edit it at any time. This note is not part of the file.")
        case .user: return AppLocalized("The agent keeps this file as its working notes about you: your name, how you like to be addressed, your timezone, and the context it needs to be useful, such as what you are working on. It fills it in over time from your conversations and reads it at the start of each one. It reflects the agent's current understanding, so it can be incomplete or out of date. You can edit anything here, and the agent will follow what you write. This note is not part of the file.")
        case .memory: return AppLocalized("Durable notes the agent keeps across conversations: the things worth remembering, in its own words, plus anything you imported from another assistant. The day-by-day diary lives beside it in the memory folder. Read at the start of every conversation when memory is on. This note is not part of the file.")
        case .feed: return AppLocalized("One paragraph that steers the feed: what you want more of, what to skip, how long posts should be. The daily feed routine reads it before writing. This note is not part of the file.")
        case .heartbeat: return AppLocalized("When the agent wakes up on its own: every routine and every goal check, with its schedule and how the last run went. Rendered from the routines, so it is read-only here; change them from Goals → Routines. This note is not part of the file.")
        }
    }

    @MainActor
    func read() -> String {
        switch self {
        case .heartbeat: return NanoMuseSystemFiles.renderHeartbeat()
        case .feed: return NanoMuseFeedStore.shared.preferences
        default: return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        }
    }

    @MainActor
    func write(_ text: String) {
        switch self {
        case .heartbeat:
            return
        case .feed:
            NanoMuseFeedStore.shared.savePreferences(text)
        case .soul:
            // Keep the front matter the app manages; only the body is edited here.
            let parsed = SoulMDParser.parse(text)
            let current = SoulStore.load()
            let file = SoulFile(metadata: text.hasPrefix("---") ? parsed.metadata : (current?.metadata ?? .default), body: text.hasPrefix("---") ? parsed.body : text)
            try? SoulStore.save(file)
        default:
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? text.write(to: url, atomically: true, encoding: .utf8)
        }
    }

    @MainActor
    var modifiedAt: Date? {
        switch self {
        case .heartbeat:
            return NanoMuseScheduler.shared.routines.compactMap { $0.lastFiredAt ?? $0.createdAt }.max()
        default:
            return (try? FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate]) as? Date
        }
    }
}

@MainActor
enum NanoMuseSystemFiles {
    /// USER.md in the system prompt — one paragraph, only when the file has content.
    static func userPromptFragment() -> String? {
        let text = NanoMuseSystemFile.user.read().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        return "## About the user (USER.md, maintained by you and the user)\n"
            + "Read at the start of every conversation. Fill it in over time from what the user tells you (name, how to address them, timezone, what they are working on, preferences); edit it with your shell at /var/minis/memory/USER.md when you learn something durable, and tell the user in one line when you do. Never write secrets into it.\n"
            + String(text.prefix(4000))
    }

    /// The routines, as the agent's heartbeat: what runs, when, and how the last run went.
    static func renderHeartbeat() -> String {
        let all = NanoMuseScheduler.shared.routines.sorted { ($0.hour * 60 + $0.minute) < ($1.hour * 60 + $1.minute) }
        let goals = NanoMuseGoalStore.shared.goals
        var s = "# HEARTBEAT.md\n\n"
        s += AppLocalized("What runs on its own, and when. Rendered from the routines; edit them from Goals → Routines. On the iPhone a routine runs when the app is open; at its time the phone reminds you to open it.") + "\n\n"
        let routines = all.filter { !$0.hidden }
        s += "## " + AppLocalized("Routines") + "\n"
        if routines.isEmpty { s += "_" + AppLocalized("none") + "_\n" }
        for r in routines {
            // the same words as the Routines list: "Daily · 08:00", "Checks every 6 hours"
            s += "- \(r.enabled ? "[x]" : "[ ]") **\(r.label.isEmpty ? AppLocalized("Routine") : r.label)** · \(r.cadence)" + lastRun(r) + "\n"
        }
        s += "\n## " + AppLocalized("Goal checks") + "\n"
        let checks = all.filter { $0.hidden && $0.goalId != nil }
        if checks.isEmpty { s += "_" + AppLocalized("none") + "_\n" }
        for r in checks {
            let g = goals.first { $0.id == r.goalId }
            s += "- \(r.enabled ? "[x]" : "[ ]") \(g?.title ?? r.label) · \(r.cadence)" + lastRun(r) + "\n"
        }
        let feedId = NanoMuseFeedFlow.routineId
        if let feed = all.first(where: { $0.id == feedId }) {
            s += "\n## " + AppLocalized("Feed") + "\n"
            s += "- \(feed.enabled ? "[x]" : "[ ]") \(feed.label) · \(feed.cadence)" + lastRun(feed) + "\n"
        }
        return s
    }

    private static func lastRun(_ r: NanoMuseRoutine) -> String {
        guard let at = r.lastFiredAt else { return "" }
        let ok = r.runs.first?.ok ?? true
        return " · " + String(format: ok ? AppLocalized("last run %@") : AppLocalized("last run %@, failed"), NanoMuseDay.relative(at))
    }

    // MARK: Import memory

    /// The section heading in the person's language; any of these is recognised when appending.
    static let importHeadings = ["## 导入", "## 匯入", "## Imported"]

    /// Pure: `existing` GLOBAL.md plus a pasted block under "## Imported" (tested).
    static func appendImported(to existing: String, pasted: String, from: String?, heading: String, date: String) -> String? {
        let body = pasted.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return nil }
        let fromPart = (from ?? "").trimmingCharacters(in: .whitespaces)
        let header = "### \(date)" + (fromPart.isEmpty ? "" : " · \(fromPart)")
        let addition = "\n\(header)\n\n\(body)\n"
        let lines = existing.components(separatedBy: "\n")
        if let headingAt = lines.firstIndex(where: { importHeadings.contains($0.trimmingCharacters(in: .whitespaces)) }) {
            // At the end of the Imported section (before the next "## " heading, if any).
            let nextAt = ((headingAt + 1)..<lines.count).first { lines[$0].hasPrefix("## ") } ?? lines.count
            let before = lines[0..<nextAt].joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            let after = lines[nextAt...].joined(separator: "\n")
            return before + "\n" + addition + (after.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : "\n" + after)
        }
        let trimmed = existing.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed.isEmpty ? "" : trimmed + "\n\n") + heading + "\n" + addition
    }

    @discardableResult
    static func importMemory(pasted: String, from: String?) -> Bool {
        let file = NanoMuseSystemFile.memory.url
        let existing = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        let date = Date().formatted(date: .abbreviated, time: .omitted)
        guard let updated = appendImported(to: existing, pasted: pasted, from: from, heading: "## " + AppLocalized("Imported"), date: date) else { return false }
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try updated.write(to: file, atomically: true, encoding: .utf8)
            return true
        } catch {
            return false
        }
    }

    static var importPrompt: String { AppLocalized("Please gather everything you remember about me into a concise list of bullet points: who I am, how I like to be addressed, where I live and my timezone, what I do, what I am working on right now, my preferences and habits, and anything you were told to always or never do. Plain text, no headings, no secrets or passwords.") }
}

// MARK: - Views

struct NanoMuseSystemFilesView: View {
    @State private var byRecent = false
    @State private var tick = 0

    private var files: [NanoMuseSystemFile] {
        let all = NanoMuseSystemFile.allCases
        guard byRecent else { return all }
        return all.sorted { ($0.modifiedAt ?? .distantPast) > ($1.modifiedAt ?? .distantPast) }
    }

    var body: some View {
        List {
            Section {
                ForEach(files) { file in
                    NavigationLink {
                        NanoMuseSystemFileView(file: file)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: file == .heartbeat ? "waveform.path.ecg" : "doc.text")
                                .foregroundStyle(NanoMuseTones.action)
                                .frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(file.fileName).font(.body.weight(.medium))
                                if let at = file.modifiedAt {
                                    Text(NanoMuseDay.relative(at)).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            } footer: {
                Text(AppLocalized("These files are the agent's: who it is, what it knows about you, what it remembers, what it writes for you and when it wakes up. They live in your phone's storage; the agent reads and edits them from its shell under /var/minis/memory, and you can edit anything here."))
            }
            Section {
                NavigationLink(AppLocalized("Import memory")) { NanoMuseMemoryImportView() }
            }
        }
        .id(tick)
        .navigationTitle(AppLocalized("System files"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    byRecent.toggle()
                } label: {
                    Image(systemName: byRecent ? "clock" : "textformat.abc")
                }
                .accessibilityLabel(Text(AppLocalized("Change order")))
            }
        }
        .onAppear { tick += 1 }
    }
}

struct NanoMuseSystemFileView: View {
    var file: NanoMuseSystemFile
    @State private var text = ""
    @State private var editing = false
    @State private var draft = ""
    @State private var copied = false
    @State private var share = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(AppLocalized("About this file.") + " " + file.about)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(NanoMuseTones.fill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                if editing {
                    TextEditor(text: $draft)
                        .font(.system(.body, design: .monospaced))
                        .frame(minHeight: 320)
                        .padding(6)
                        .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Text(String(format: AppLocalized("Write %@ in Markdown. Headings and bullet points read best; keep secrets out."), file.fileName))
                        .font(.caption).foregroundStyle(.secondary)
                } else if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(file.editable ? AppLocalized("Nothing here yet. Tap the pencil to write it, or let the agent fill it in as you talk.") : AppLocalized("Nothing here yet."))
                        .font(.body).foregroundStyle(.secondary)
                        .padding(.top, 20)
                } else {
                    Text(text)
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(16)
        }
        .background(NanoMuseTones.canvas.ignoresSafeArea())
        .navigationTitle(file.fileName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if editing {
                    Button(AppLocalized("Save")) {
                        file.write(draft)
                        text = file.read()
                        editing = false
                    }
                } else {
                    Menu {
                        if file.editable {
                            Button { draft = text; editing = true } label: { Label(AppLocalized("Edit"), systemImage: "pencil") }
                        }
                        Button {
                            UIPasteboard.general.string = text
                            copied = true
                        } label: { Label(copied ? AppLocalized("Copied") : AppLocalized("Copy contents"), systemImage: "doc.on.doc") }
                        Button { share = true } label: { Label(AppLocalized("Share"), systemImage: "square.and.arrow.up") }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .onAppear { text = file.read() }
        .sheet(isPresented: $share) {
            NanoMuseShareSheet(items: [text])
        }
    }
}


struct NanoMuseMemoryImportView: View {
    @State private var from = ""
    @State private var pasted = ""
    @State private var result: String?
    @State private var copiedPrompt = false

    var body: some View {
        Form {
            Section {
                Text(AppLocalized("Bring over what another assistant already knows about you. Ask it with the prompt below, paste its answer here, and it is appended to GLOBAL.md under “Imported”, where your agent reads it at the start of every conversation."))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section(AppLocalized("Ask your other assistant")) {
                Text(NanoMuseSystemFiles.importPrompt).font(.footnote)
                Button(copiedPrompt ? AppLocalized("Copied") : AppLocalized("Copy prompt")) {
                    UIPasteboard.general.string = NanoMuseSystemFiles.importPrompt
                    copiedPrompt = true
                }
            }
            Section(AppLocalized("Paste its answer")) {
                TextField(AppLocalized("From (optional), e.g. ChatGPT, Claude"), text: $from)
                TextEditor(text: $pasted).frame(minHeight: 140)
                    .overlay(alignment: .topLeading) {
                        if pasted.isEmpty {
                            Text(AppLocalized("Paste the list here")).foregroundStyle(.tertiary).padding(.top, 8).padding(.leading, 4).allowsHitTesting(false)
                        }
                    }
            }
            Section {
                Button(AppLocalized("Add to memory")) {
                    if NanoMuseSystemFiles.importMemory(pasted: pasted, from: from) {
                        result = AppLocalized("Added to GLOBAL.md")
                        pasted = ""
                    } else {
                        result = AppLocalized("Nothing to add")
                    }
                }
                .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let result { Text(result).font(.footnote).foregroundStyle(.secondary) }
            } footer: {
                Text(AppLocalized("Look it over first: leave out passwords, card numbers and anything you would not want in a plain text file on your phone."))
            }
        }
        .navigationTitle(AppLocalized("Import memory"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
