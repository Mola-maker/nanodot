//
//  NanoMuseChatRename.swift
//  Nanodot
//
//  Renaming a chat from the shell: the main chat's ••• menu and the drawer's
//  long-press. The name is upstream's session title (ChatStore), so the
//  classic list, the drawer, the header and the sync engine all read the
//  same field. Android: the rename dialog in ChatScreen's menu and the
//  drawer's long-press.
//

import SwiftUI

/// The prompt's state: which session, the draft name, whether the alert is up.
@MainActor
final class NanoMuseRenamePrompt: ObservableObject {
    @Published var isPresented = false
    @Published var title = ""
    private(set) var sessionId: String?

    /// Loads the current title, then raises the alert.
    func open(_ sessionId: String) {
        self.sessionId = sessionId
        Task { @MainActor in
            let session = await ChatStore.shared.getSession(sessionId)
            title = session?.title ?? ""
            isPresented = true
        }
    }

    /// Saves the trimmed name through upstream's title update (which posts
    /// `.sessionDidUpdate`), then tells the sync engine the conversation changed.
    func save() {
        guard let id = sessionId else { return }
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        Task { @MainActor in
            let current = await ChatStore.shared.getSession(id)
            await ChatStore.shared.updateSessionTitle(id, title: name, category: current?.category)
            NanoMuseSync.shared.conversationChanged(id)
        }
    }
}

/// `.alert` with a text field, bound to a `NanoMuseRenamePrompt`.
struct NanoMuseRenameAlert: ViewModifier {
    @ObservedObject var prompt: NanoMuseRenamePrompt

    func body(content: Content) -> some View {
        content.alert(AppLocalized("Rename chat"), isPresented: $prompt.isPresented) {
            TextField(AppLocalized("Chat name"), text: $prompt.title)
            Button(AppLocalized("Cancel"), role: .cancel) {}
            Button(AppLocalized("Save")) { prompt.save() }
        }
    }
}

extension View {
    /// Presents the rename alert for `prompt`.
    func nmRenameAlert(_ prompt: NanoMuseRenamePrompt) -> some View {
        modifier(NanoMuseRenameAlert(prompt: prompt))
    }
}

/// Deleting a chat from the drawer: upstream's delete, then the sync tombstone.
enum NanoMuseChatDelete {
    @MainActor
    static func delete(_ sessionId: String) {
        Task { @MainActor in
            NanoMuseSync.shared.conversationWillBeDeleted(sessionId)
            await ChatStore.shared.deleteSession(sessionId)
            NotificationCenter.default.post(name: .sessionDidUpdate, object: nil)
        }
    }
}
