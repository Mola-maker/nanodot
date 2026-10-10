//
//  NanoMuseChatCards.swift
//  Nanodot
//
//  Cards in the chat. Two kinds: the *virtual* ones that sit above the
//  composer while something is in flight (the avatar's price, its four
//  takes, the share card after a new face, the name chooser) and are never
//  persisted; and the *fence* cards — a `nanomuse-goal`, `-goal-update`,
//  `-feed` or `-avatar` block the model or the app wrote into an assistant
//  turn, rendered where the text would be.
//  Android: ui/chat/NanoMuseBlock.kt and the nm*Card messages in ChatViewModel.
//

import SwiftUI

// MARK: - Virtual cards above the composer

/// Hosted by AIChatView just above the input bar. Also where the two
/// notifications from the agent page land: a prefilled composer and the
/// first conversation's seeding.
struct NanoMuseChatCardsHost: View {
    @ObservedObject var vm: AIChatViewModel
    @ObservedObject private var flow = NanoMuseAvatarFlow.shared
    @ObservedObject private var naming = NanoMuseFirstConversation.shared
    @ObservedObject private var allowance = NanoMuseAllowance.shared

    private var key: String { vm.nmSessionKey }

    var body: some View {
        VStack(spacing: 8) {
            // C11 / parity #33: 80 % of the free allowance spent — one line, once per pool size.
            if let line = allowance.headsUp, allowance.pending == nil {
                NanoMuseAllowanceHeadsUp(text: line) { allowance.hideHeadsUp() }
            }
            switch flow.stage {
            case .confirming(let s, _) where s == key:
                NanoMuseAvatarCostCard()
            case .choosing(let s, _) where s == key, .finalizing(let s, _, _) where s == key:
                NanoMuseAvatarOptionsCard()
            case .done(let s, let desc) where s == key:
                NanoMuseAvatarDoneCard(description: desc)
            default:
                EmptyView()
            }
            if naming.card != nil, naming.isBound(to: key) {
                NanoMuseNamingCardView(vm: vm)
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 6)
        .onReceive(NotificationCenter.default.publisher(for: .nanoMuseComposerPrefill)) { note in
            // Meant for this chat: the object is the session key it was posted for, or nil for "whichever is open".
            if let target = note.object as? String, target != key, target != vm.sessionId, target != vm.draftId { return }
            guard let text = note.userInfo?["text"] as? String else { return }
            if (note.userInfo?["send"] as? Bool) == true { vm.nmSendNow(text) } else { vm.nmPrefill(text) }
        }
        .task(id: key) {
            await naming.seedIfNeeded(vm: vm)
        }
    }
}

// MARK: - The agent's bubble

/// Muse's transcript: the agent's prose sits in a grey bubble (Android:
/// NmAssistantBubble, MuseTones.bubble, radius 20, at most 340 wide); a block
/// that is a code fence, a table or raw HTML stays bare, as does everything
/// in the classic layout.
struct NanoMuseAssistantBubble: ViewModifier {
    var content: String

    /// Code, tables and markup read better without the bubble.
    static func isBare(_ content: String) -> Bool {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") || trimmed.hasPrefix("<") { return true }
        return trimmed.hasPrefix("|") && trimmed.contains("|\n|")
    }

    @ViewBuilder
    func body(content view: Content) -> some View {
        if NanoMuseShellPrefs.shell && !Self.isBare(content) {
            view
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(NanoMuseTones.bubble, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .frame(maxWidth: 340, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            view
        }
    }
}

// MARK: - Fence cards inside an assistant turn

/// An assistant text block that carries one or more `nanomuse-*` fences: prose stays
/// Markdown, each fence becomes its card. Unknown kinds show their JSON so nothing is lost.
struct NanoMuseFenceBlockView: View {
    var content: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(NanoMuseFences.pieces(content).enumerated()), id: \.offset) { _, piece in
                switch piece {
                case .prose(let text):
                    NanoMuseInlineMarkdown(text: text)
                        .font(.body)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                case .block(let kind, let json):
                    NanoMuseFenceCard(kind: kind, json: json)
                }
            }
        }
    }
}

struct NanoMuseFenceCard: View {
    var kind: String
    var json: String

    private var object: [String: Any]? {
        guard let data = json.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    var body: some View {
        if let o = object {
            switch kind {
            case NanoMuseGoalFlow.blockGoal: NanoMuseGoalCreatedCard(object: o)
            case NanoMuseGoalFlow.blockUpdate: NanoMuseGoalUpdateCard(object: o)
            case NanoMuseFeedFlow.block: NanoMuseFeedFenceCard(object: o)
            case NanoMuseAvatarFlow.blockOptions: NanoMuseAvatarFenceCard(object: o)
            case NanoMuseFirstConversation.block: EmptyView() // for the app (FirstConversation.afterTurn), not for the eye
            default: raw
            }
        } else if kind == NanoMuseFirstConversation.block {
            EmptyView()
        } else {
            raw
        }
    }

    private var raw: some View {
        Text(json)
            .font(.system(.footnote, design: .monospaced))
            .foregroundStyle(.secondary)
            .textSelection(.enabled)
    }
}

/// The rounded frame every fence card sits in.
struct NanoMuseCardFrame<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(NanoMuseTones.hairline))
            .padding(.vertical, 4)
    }
}

/// "Goal created": title, why, up to five steps, the cadence, a way into Goals.
struct NanoMuseGoalCreatedCard: View {
    var object: [String: Any]

    var body: some View {
        let title = object["title"] as? String ?? ""
        let why = object["why"] as? String ?? ""
        let steps = ((object["steps"] as? [Any]) ?? []).compactMap { $0 as? String }.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let everyHours = (object["check_every_hours"] as? NSNumber)?.intValue ?? Int(object["check_every_hours"] as? String ?? "") ?? 0
        let checkTime = (object["check_time"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "09:00"
        NanoMuseCardFrame {
            HStack(spacing: 8) {
                Image(systemName: "flag").font(.footnote.weight(.semibold)).foregroundStyle(NanoMuseTones.action)
                Text(AppLocalized("Goal created")).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            }
            Text(title).font(.body.weight(.semibold)).padding(.top, 6)
            if !why.isEmpty {
                Text(why).font(.footnote).foregroundStyle(.secondary).padding(.top, 2)
            }
            if !steps.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(steps.prefix(5).enumerated()), id: \.offset) { _, step in
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "square").font(.footnote).foregroundStyle(.secondary).padding(.top, 2)
                            Text(step).font(.footnote)
                        }
                    }
                }
                .padding(.top, 8)
            }
            HStack {
                Text(everyHours > 0
                     ? (everyHours == 1 ? AppLocalized("Checks every hour") : String(format: AppLocalized("Checks every %d hours"), everyHours))
                     : String(format: AppLocalized("Checks daily at %@"), checkTime))
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(AppLocalized("See in Goals")) {
                    NotificationCenter.default.post(name: .nanoMuseOpenRoom, object: "goals")
                }
                .font(.footnote)
            }
            .padding(.top, 6)
        }
    }
}

/// A check-in's outcome: status, progress bar, the note.
struct NanoMuseGoalUpdateCard: View {
    var object: [String: Any]

    var body: some View {
        let progressRaw = (object["progress"] as? NSNumber)?.intValue ?? Int(object["progress"] as? String ?? "") ?? -1
        let progress = min(max(progressRaw, -1), 100)
        let status = (object["status"] as? String ?? "on_track").lowercased()
        let note = object["note"] as? String ?? ""
        let done = status == "done"
        let attention = status == "attention"
        let tint: Color = attention ? .red : NanoMuseTones.action
        NanoMuseCardFrame {
            HStack(spacing: 8) {
                Image(systemName: done ? "checkmark.square" : "chart.line.uptrend.xyaxis").font(.footnote.weight(.semibold)).foregroundStyle(tint)
                Text(AppLocalized("Goal update")).font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Spacer()
                Text(done ? AppLocalized("Done") : attention ? AppLocalized("Needs attention") : AppLocalized("On track"))
                    .font(.caption.weight(.medium)).foregroundStyle(tint)
            }
            if progress >= 0 {
                ProgressView(value: Double(progress), total: 100)
                    .tint(tint)
                    .padding(.top, 10)
            }
            if !note.isEmpty {
                Text(note).font(.footnote).padding(.top, 8)
            }
        }
    }
}
