//
//  NanoMuseAvatarFlow.swift
//  Nanodot
//
//  Changing the face from inside the conversation, the way Muse does it: a
//  message such as "把虚拟形象换成一只小狗" / "change your avatar to a corgi"
//  is not sent to the model. It starts this flow instead — four candidates
//  drawn in the house style, a card in the chat to pick from (by tap, or by
//  typing "第二个" / "option 2"), then the chosen one becomes the face and
//  its poses are drawn in the background. Android: avatar/AvatarFlow.kt.
//

import Combine
import Foundation
import SwiftUI
import UIKit

// MARK: - Intent (pure, tested)

enum NanoMuseAvatarIntent {
    enum Choice: Equatable {
        case index(Int)
        case regenerate
    }

    private static func re(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression {
        // The patterns are literals; a typo would show up in the unit tests.
        try! NSRegularExpression(pattern: pattern, options: options)
    }

    private static let zhRequest: [NSRegularExpression] = [
        // 把/将 (你的/我的)? (虚拟)?形象 (改|换|变|更换|切换|设置)(成|为|到) X
        re("^(?:请|麻烦|帮我|帮忙|可以|能不能|能否)?\\s*(?:把|将)?\\s*(?:你的|你|我的|我)?\\s*(?:虚拟)?(?:形象|头像|样子|外形)\\s*(?:改|换|变|更换|切换|设置|设定|变更)(?:成|为|到|一下成|一下为)?\\s*(.+)$"),
        // 换个/换一个 (新)?形象[：,] X
        re("^(?:请|麻烦|帮我|帮忙)?\\s*(?:换|变|改)(?:个|一个|一下)?\\s*(?:新的?)?(?:虚拟)?(?:形象|头像)\\s*[：:，,、]?\\s*(.+)$"),
        re("^(?:请|麻烦|帮我|帮忙)?\\s*(?:变成|化身为|变身为|变身成)\\s*(.+?)\\s*(?:的)?(?:形象|样子|头像)?$"),
    ]
    private static let enRequest: [NSRegularExpression] = [
        re("^(?:please\\s+)?(?:can you\\s+)?(?:change|switch|set|update|turn|make|transform)\\s+(?:your|the|my|ur)?\\s*(?:virtual\\s+)?(?:avatar|appearance|look|character)\\s+(?:to|into)\\s+(.+)$", .caseInsensitive),
        re("^(?:please\\s+)?(?:new|another)\\s+(?:virtual\\s+)?avatar\\s*[:,-]?\\s*(.+)$", .caseInsensitive),
        re("^(?:please\\s+)?(?:become|be)\\s+(.+)$", .caseInsensitive),
    ]
    private static let article = re("^(an?|the)\\s+", .caseInsensitive)
    private static let subjectWords = re("\\b(cat|dog|puppy|kitten|robot|bear|panda|fox|rabbit|bunny|bird|dragon|penguin|owl|corgi|shiba|husky|otter|character|creature|monster|alien)\\b", .caseInsensitive)
    private static let trailing: Set<Character> = ["。", ".", "!", "！", "~", "～", "吧", "呗", "呀", "哦", "啊", "嘛"]

    private static func match(_ regex: NSRegularExpression, _ s: String) -> (whole: String, group: String)? {
        let ns = s as NSString
        guard let m = regex.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges > 1,
              m.range(at: 1).location != NSNotFound else { return nil }
        return (ns.substring(with: m.range), ns.substring(with: m.range(at: 1)))
    }

    private static func trimTrailing(_ s: String, _ set: Set<Character>) -> String {
        var t = s
        while let last = t.last, set.contains(last) { t.removeLast() }
        return t
    }

    /// The description of the new face if `text` asks for one, else nil.
    static func parseRequest(_ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t.count > 400 || t.contains("\n") { return nil }
        var hit: (whole: String, group: String)?
        for regex in zhRequest + enRequest {
            if let m = match(regex, t) { hit = m; break }
        }
        guard let hit else { return nil }
        var desc = trimTrailing(hit.group.trimmingCharacters(in: .whitespaces), trailing)
        // English articles go; "一只小狗" reads better than "小狗" in the announcement, so Chinese counters stay.
        desc = article.stringByReplacingMatches(in: desc, range: NSRange(location: 0, length: (desc as NSString).length), withTemplate: "")
            .trimmingCharacters(in: .whitespaces)
        if desc.count < 1 || desc.count > 200 { return nil }
        let lowerWhole = hit.whole.lowercased()
        // "be quiet", "become better": the bare verbs only with something that reads like a subject.
        if lowerWhole.hasPrefix("be ") || lowerWhole.hasPrefix("become ") {
            if subjectWords.firstMatch(in: desc, range: NSRange(location: 0, length: (desc as NSString).length)) == nil { return nil }
        }
        if hit.whole.hasPrefix("变成") || hit.whole.hasPrefix("化身") || hit.whole.hasPrefix("变身") {
            if desc.count < 2 { return nil }
        }
        return desc
    }

    private static let ordinalZh: [String: Int] = ["一": 0, "1": 0, "二": 1, "两": 1, "2": 1, "三": 2, "3": 2, "四": 3, "4": 3]
    private static let ordinalEn: [String: Int] = ["first": 0, "1st": 0, "second": 1, "2nd": 1, "third": 2, "3rd": 2, "fourth": 3, "4th": 3, "last": 3]
    private static let corners: [(String, Int)] = [("左上", 0), ("右上", 1), ("左下", 2), ("右下", 3), ("top left", 0), ("top right", 1), ("bottom left", 2), ("bottom right", 3)]

    private static let regenerateWords = re("^(重新生成|再来一组|再生成|换一批|都不喜欢|都不好|都不要|再来四个|重来|regenerate|try again|another set|none of (these|them)|new options)$")
    private static let zhOrdinal = re("^(?:就|选|要|我要|我选|用|我喜欢|喜欢)?\\s*第\\s*([一二两三四1234])\\s*(?:个|只|张|款|号)?(?:吧|好了|好)?$")
    private static let zhDigit = re("^(?:就|选|要|我要|我选|用)?\\s*([1-4])\\s*(?:号|个|只|张)?(?:吧|好了|好)?$")
    private static let enDigit = re("^(?:i(?:'ll| will)? (?:take|pick|choose|like|want)|pick|choose|take|use|go with)?\\s*(?:the\\s+)?(?:option|number|no\\.?|#)?\\s*([1-4])$")
    private static let enOrdinal = re("^(?:i(?:'ll| will)? (?:take|pick|choose|like|want)|pick|choose|take|use|go with)?\\s*(?:the\\s+)?(first|second|third|fourth|last|1st|2nd|3rd|4th)(?:\\s+one)?$")

    /// While candidates are up: which one the person means, or that they want a new set.
    static func parseChoice(_ text: String) -> Choice? {
        let t = trimTrailing(text.trimmingCharacters(in: .whitespacesAndNewlines), ["。", ".", "!", "！", "~", "～", "吧", "呗", "呀", "哦", "啊"])
        if t.isEmpty || t.count > 40 { return nil }
        let lower = t.lowercased()
        if match(regenerateWords, lower) != nil { return .regenerate }
        if let m = match(zhOrdinal, t), let i = ordinalZh[m.group] { return .index(i) }
        if let m = match(zhDigit, t), let n = Int(m.group) { return .index(n - 1) }
        if let m = match(enDigit, lower), let n = Int(m.group) { return .index(n - 1) }
        if let m = match(enOrdinal, lower), let i = ordinalEn[m.group] { return .index(i) }
        if t.count <= 8, let corner = corners.first(where: { lower.contains($0.0) }) { return .index(corner.1) }
        return nil
    }
}

// MARK: - Flow

@MainActor
final class NanoMuseAvatarFlow: ObservableObject {
    static let shared = NanoMuseAvatarFlow()
    static let blockOptions = "avatar"

    enum Stage: Equatable {
        case idle
        /// The relay would charge the allowance: the cost card is up.
        case confirming(session: String, description: String)
        /// Candidates are being drawn or waiting to be picked.
        case choosing(session: String, description: String)
        /// One was picked; the poses are being drawn.
        case finalizing(session: String, description: String, chosen: Int)
        /// The new face is on: the share card is up until dismissed.
        case done(session: String, description: String)

        var session: String? {
            switch self {
            case .idle: return nil
            case .confirming(let s, _), .choosing(let s, _), .finalizing(let s, _, _), .done(let s, _): return s
            }
        }
    }

    @Published private(set) var stage: Stage = .idle
    private weak var vm: AIChatViewModel?
    private var watcher: AnyCancellable?
    private var announcedRound = false

    private var studio: NanoMuseAvatarStudioModel { .shared }

    private init() {
        watcher = studio.$phase
            .receive(on: RunLoop.main)
            .sink { [weak self] phase in self?.studioPhaseChanged(phase) }
    }

    func isActive(in session: String) -> Bool { stage.session == session && stage != .idle }

    /// The draft the flow started in became a real session: follow it.
    func rebind(from draft: String, to real: String) {
        switch stage {
        case .confirming(let s, let d) where s == draft: stage = .confirming(session: real, description: d)
        case .choosing(let s, let d) where s == draft: stage = .choosing(session: real, description: d)
        case .finalizing(let s, let d, let c) where s == draft: stage = .finalizing(session: real, description: d, chosen: c)
        case .done(let s, let d) where s == draft: stage = .done(session: real, description: d)
        default: break
        }
    }

    /// Muse's status under the name while the flow runs.
    var statusLine: String? {
        switch stage {
        case .choosing: return studio.phase == .drawing ? AppLocalized("Generating options") : nil
        case .finalizing: return AppLocalized("Finalizing avatar")
        default: return nil
        }
    }

    // MARK: Chat entry

    /// A message typed into `vm`: a request or a choice is taken here and never reaches the model.
    func handle(_ text: String, in vm: AIChatViewModel) -> Bool {
        let session = vm.nmSessionKey
        if case .choosing(let s, _) = stage, s == session, studio.phase == .pick, let choice = NanoMuseAvatarIntent.parseChoice(text) {
            switch choice {
            case .index(let i): choose(i, typed: text)
            case .regenerate: regenerate(typed: text)
            }
            return true
        }
        guard let desc = NanoMuseAvatarIntent.parseRequest(text) else { return false }
        self.vm = vm
        // The first conversation's "what should I call you?" is not answered by this.
        if NanoMuseFirstConversation.shared.isBound(to: session) { NanoMuseFirstConversation.shared.dismissChooser() }
        if let reason = studio.cannotDrawReason {
            vm.nmLocalTurn(user: text, assistant: String(format: AppLocalized("I can't draw a new look yet: %@"), reason))
            return true
        }
        if studio.isBusy {
            vm.nmLocalTurn(user: text, assistant: AppLocalized("Give me a moment. I'm still drawing the last one."))
            return true
        }
        if studio.usesOwnKey {
            vm.nmLocalTurn(user: text, assistant: String(format: AppLocalized("I'm drawing a few takes on \"%@\" now. Pictures take a little while; I'll tell you when they're ready."), desc))
            begin(session: session, description: desc)
        } else {
            vm.nmLocalTurn(user: text)
            studio.refreshEstimate()
            stage = .confirming(session: session, description: desc)
        }
        return true
    }

    /// The person tapped "Draw it" on the cost card.
    func confirmDraw() {
        guard case .confirming(let session, let desc) = stage else { return }
        vm?.nmLocalTurn(assistant: String(format: AppLocalized("I'm drawing a few takes on \"%@\" now. Pictures take a little while; I'll tell you when they're ready."), desc))
        begin(session: session, description: desc)
    }

    /// "Not now" on the cost card, "Keep current" under the options.
    func cancel() {
        if case .choosing = stage, studio.phase == .pick || studio.phase == .drawing { studio.backToDescribe() }
        stage = .idle
    }

    func dismissShare() {
        if case .done = stage { stage = .idle }
    }

    private func begin(session: String, description: String) {
        announcedRound = false
        if studio.start(description: description, style: .muse) {
            stage = .choosing(session: session, description: description)
        } else {
            stage = .idle
            vm?.nmLocalTurn(assistant: String(format: AppLocalized("I can't draw a new look yet: %@"), studio.lastError ?? AppLocalized("The pictures did not come through. Try again in a minute.")))
        }
    }

    /// A tap on a candidate, or a typed "the second one".
    func choose(_ index: Int, typed: String? = nil) {
        guard case .choosing(let session, let desc) = stage, index >= 0, index < studio.candidates.count else { return }
        guard studio.candidates[index].image != nil else { return }
        let files = studio.candidateFiles
        guard studio.adopt(index: index) else { return }
        stage = .finalizing(session: session, description: desc, chosen: index)
        let line = typed ?? String(format: AppLocalized("Option %d"), index + 1)
        let fence = NanoMuseFences.fence(Self.blockOptions, ["desc": desc, "chosen": index, "files": files])
        var reply = String(format: AppLocalized("Done. My new look is on. I'm %@ now."), desc)
        // With a video model set the clips follow in the background (NanoMuseAvatarMotion); say so,
        // as Android's `nm_avatar_clips_coming` does.
        if NanoMuseAvatarMotion.shared.enabled {
            reply += " " + AppLocalized("I'll also make four short clips so I can move. That takes a few minutes in the background, through your video model.")
        }
        vm?.nmLocalTurn(user: line, assistant: reply + "\n\n" + fence)
        appendMemory(desc)
    }

    func regenerate(typed: String? = nil) {
        guard case .choosing = stage, !studio.isBusy else { return }
        vm?.nmLocalTurn(user: typed, assistant: AppLocalized("One more round. Give me a moment."))
        announcedRound = false
        studio.draw()
    }

    private func studioPhaseChanged(_ phase: NanoMuseAvatarStudioModel.Phase) {
        switch (stage, phase) {
        case (.choosing(_, let desc), .pick) where !announcedRound:
            announcedRound = true
            vm?.nmLocalTurn(assistant: String(format: AppLocalized("Here are a few takes on \"%@\". Pick the one you like best."), desc))
        case (.choosing, .describe):
            // Every candidate failed; the studio went back to describe.
            if let error = studio.lastError {
                vm?.nmLocalTurn(assistant: AppLocalized("The pictures did not come through. Check the image model under the agent page → Avatar studio and try again.") + "\n" + error)
            }
            stage = .idle
        case (.finalizing(let session, let desc, _), .finished):
            stage = .done(session: session, description: desc)
        default:
            break
        }
    }

    /// One line in GLOBAL.md about the change, so the agent remembers what it looks like.
    private func appendMemory(_ description: String) {
        let file = NanoMuseDirs.memory.appendingPathComponent("GLOBAL.md")
        let line = String(format: AppLocalized("- %@: the user changed my avatar to \"%@\"."), NanoMuseDay.key(), description)
        let current = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        let next = current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? line : current.trimmingCharacters(in: .newlines) + "\n" + line
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? (next + "\n").write(to: file, atomically: true, encoding: .utf8)
    }
}

// MARK: - Cards in the chat

/// The four candidates while they are drawn and picked (a live card, not persisted).
struct NanoMuseAvatarOptionsCard: View {
    @ObservedObject private var studio = NanoMuseAvatarStudioModel.shared
    @ObservedObject private var flow = NanoMuseAvatarFlow.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if case .choosing(_, let desc) = flow.stage {
                if studio.phase == .drawing {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text(String(format: AppLocalized("Drawing four takes on \"%@\"…"), desc))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                } else {
                    Text(AppLocalized("Tap the one you like, or tell me: \"the second one\"."))
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                ForEach(Array(studio.candidates.enumerated()), id: \.element.id) { index, candidate in
                    Button {
                        if candidate.image != nil { flow.choose(index) }
                    } label: {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(NanoMuseTones.fill)
                            if let image = candidate.image {
                                Image(uiImage: image).resizable().scaledToFill()
                                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            } else if candidate.drawing {
                                ProgressView()
                            } else {
                                VStack(spacing: 4) {
                                    Image(systemName: "exclamationmark.triangle").foregroundStyle(.secondary)
                                    Button(AppLocalized("Retry")) { studio.retry(index) }.font(.footnote)
                                }
                            }
                        }
                        .aspectRatio(1, contentMode: .fit)
                        .overlay(alignment: .bottomLeading) {
                            Text(String(format: AppLocalized("Option %d"), index + 1))
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6).padding(.vertical, 3)
                                .background(.ultraThinMaterial, in: Capsule())
                                .padding(6)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(String(format: AppLocalized("Option %d"), index + 1)))
                }
            }
            if studio.phase == .pick {
                HStack(spacing: 10) {
                    Button(AppLocalized("Again")) { flow.regenerate() }
                    Spacer()
                    Button(AppLocalized("Keep current")) { flow.cancel() }
                }
                .font(.footnote.weight(.medium))
                .tint(NanoMuseTones.action)
            }
        }
        .padding(12)
        .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// The relay's price before anything is drawn (own-key phones never see it).
struct NanoMuseAvatarCostCard: View {
    @ObservedObject private var studio = NanoMuseAvatarStudioModel.shared
    @ObservedObject private var flow = NanoMuseAvatarFlow.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(AppLocalized("Before drawing")).font(.subheadline.weight(.semibold))
            let what = studio.costWhat
            if studio.estimating {
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text(AppLocalized("Checking today's allowance…")).foregroundStyle(.secondary) }
                    .font(.footnote)
            } else if let e = studio.estimate {
                if e.unlimited {
                    Text(what + ".").font(.footnote)
                } else {
                    Text(String(format: AppLocalized("%@: about ¥%@ from your allowance."), what, NanoMuseFaceCostSheet.money(e.cny))).font(.footnote)
                    if let left = e.leftCny {
                        Text(String(format: AppLocalized("You have ¥%@ of the allowance left."), NanoMuseFaceCostSheet.money(left))).font(.footnote).foregroundStyle(.secondary)
                    }
                    if !e.affordable {
                        Text(AppLocalized("That is more than what is left. Invite a friend for more allowance, or wait for tomorrow's share."))
                            .font(.footnote).foregroundStyle(.red)
                    }
                }
            } else {
                Text(String(format: AppLocalized("%@. Today's allowance could not be checked right now."), what)).font(.footnote)
            }
            HStack(spacing: 10) {
                Button(studio.estimate?.affordable == false ? AppLocalized("Try anyway") : AppLocalized("Draw it")) { flow.confirmDraw() }
                    .buttonStyle(.borderedProminent)
                    .tint(NanoMuseTones.action).foregroundStyle(NanoMuseTones.onAction)
                    .disabled(studio.estimating)
                Button(AppLocalized("Not now")) { flow.cancel() }
            }
            .font(.footnote.weight(.medium))
        }
        .padding(12)
        .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// A persisted `nanomuse-avatar` fence: the four takes, the chosen one marked.
struct NanoMuseAvatarFenceCard: View {
    var object: [String: Any]

    private var chosen: Int { (object["chosen"] as? NSNumber)?.intValue ?? -1 }
    private var files: [String] { (object["files"] as? [String]) ?? [] }
    private var desc: String { object["desc"] as? String ?? "" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !desc.isEmpty {
                Text(String(format: AppLocalized("New look: %@"), desc)).font(.footnote.weight(.semibold))
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                ForEach(Array(files.enumerated()), id: \.offset) { index, path in
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous).fill(NanoMuseTones.fill)
                        if !path.isEmpty, let image = UIImage(contentsOfFile: path) {
                            Image(uiImage: image).resizable().scaledToFill()
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        } else {
                            Image(systemName: "photo").foregroundStyle(.tertiary)
                        }
                    }
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .strokeBorder(index == chosen ? NanoMuseTones.action : Color.clear, lineWidth: 2)
                    }
                }
            }
        }
        .padding(10)
        .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
