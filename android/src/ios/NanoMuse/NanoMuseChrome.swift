//
//  NanoMuseChrome.swift
//  Nanodot
//
//  Muse's page furniture, shared by the home shell and the settings pages:
//  the big-face header (face on a disc, the name in a white pill under the
//  chin with the status as its second line, a round button in each top
//  corner), the round button itself, the Muse top bar with a centred title
//  and a back disc, and the white cards with rows that Settings is made of.
//  Android: ui/home/MuseHeader.kt, ui/muse/MuseChrome.kt.
//

import SwiftUI

// MARK: - onChange across iOS versions

extension View {
    /// `onChange` handed the new value only. The iOS 17 form where it exists,
    /// the earlier one below it: the app's deployment target is iOS 16.0
    /// (Minis.xcodeproj), where the two-parameter form alone does not compile
    /// and the one-parameter form carries no deprecation warning.
    @ViewBuilder
    func nmOnChange<V: Equatable>(of value: V, perform action: @escaping (V) -> Void) -> some View {
        if #available(iOS 17.0, *) {
            onChange(of: value) { _, newValue in action(newValue) }
        } else {
            onChange(of: value, perform: action)
        }
    }
}

// MARK: - Wrapping row

/// A row of small things (chips, the buttons under a card) that wraps to the next line when
/// the width runs out, so four names or three actions fit an iPhone SE and a large text size
/// without being cut off. Leading-aligned; `spacing` between items and between lines.
struct NanoMuseFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var lineHeight: CGFloat = 0
        var widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += lineHeight + spacing
                lineHeight = 0
            }
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: width == .infinity ? widest : min(widest, width), height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        // Lines first, then each item centred on its line (a filled button next to a plain one).
        var lines: [[(index: Int, size: CGSize)]] = [[]]
        var x: CGFloat = 0
        for (i, view) in subviews.enumerated() {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > bounds.width {
                lines.append([])
                x = 0
            }
            lines[lines.count - 1].append((i, size))
            x += size.width + spacing
        }
        var y = bounds.minY
        for line in lines {
            let lineHeight = line.map(\.size.height).max() ?? 0
            var lx = bounds.minX
            for item in line {
                let dy = (lineHeight - item.size.height) / 2
                subviews[item.index].place(at: CGPoint(x: lx, y: y + dy), proposal: .unspecified)
                lx += item.size.width + spacing
            }
            y += lineHeight + spacing
        }
    }
}

// MARK: - Round button

/// Muse's corner disc: white, one glyph, a soft shadow (a hairline in the dark).
struct NanoMuseRoundDisc: View {
    var symbol: String
    var badge: Bool = false
    var size: CGFloat = 44

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Circle()
                .fill(NanoMuseTones.surface)
                .overlay(Circle().strokeBorder(NanoMuseTones.hairline, lineWidth: scheme == .dark ? 1 : 0))
                .shadow(color: .black.opacity(scheme == .dark ? 0 : 0.14), radius: 3, y: 1)
                .overlay(
                    Image(systemName: symbol)
                        .font(.system(size: size * 0.41, weight: .medium))
                        .foregroundStyle(Color.primary)
                )
            if badge {
                Circle()
                    .fill(NanoMuseTones.action)
                    .frame(width: 9, height: 9)
                    .overlay(Circle().strokeBorder(NanoMuseTones.surface, lineWidth: 1.5))
                    .padding(2)
            }
        }
        .frame(width: size, height: size)
        .contentShape(Circle())
    }
}

/// The disc as a button.
struct NanoMuseRoundButton: View {
    var symbol: String
    var label: String
    var badge: Bool = false
    var size: CGFloat = 44
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            NanoMuseRoundDisc(symbol: symbol, badge: badge, size: size)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

// MARK: - Name pill

/// Muse's name tag: a small white tag, the name in regular weight, a faint
/// shadow; what the agent is doing right now as a smaller second line.
struct NanoMuseNamePill: View {
    var name: String
    var status: String?
    var statusColor: Color = .secondary

    // Dynamic Type moves the pill's two lines with the person's text size, up to the cap in
    // NanoMuseHeaderMetrics so the header's reserved height stays enough for them.
    @ScaledMetric(relativeTo: .subheadline) private var nameSize: CGFloat = 14
    @ScaledMetric(relativeTo: .caption) private var statusSize: CGFloat = 11.5

    var body: some View {
        VStack(spacing: 2) {
            Text(name)
                .font(.system(size: NanoMuseHeaderMetrics.capped(nameSize, base: 14)))
                .foregroundStyle(Color.primary)
                .lineLimit(1)
            if let status, !status.isEmpty {
                Text(status)
                    .font(.system(size: NanoMuseHeaderMetrics.capped(statusSize, base: 11.5)))
                    .foregroundStyle(statusColor)
                    .lineLimit(1)
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .frame(maxWidth: 260)
        .fixedSize(horizontal: true, vertical: false)
        .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(color: .black.opacity(0.14), radius: 2, y: 1)
        .animation(.easeInOut(duration: 0.2), value: status)
    }
}

// MARK: - Big-face header

/// How far the name pill rides up over the chin of the face.
private let nmPillOverlap: CGFloat = 10

/// The header's sizes under Dynamic Type: the pill's text and the height reserved for it grow
/// with the person's setting up to `scaleCap` times their base, so the largest accessibility
/// sizes make the lines readable without pushing the face off its disc.
enum NanoMuseHeaderMetrics {
    /// The largest multiple of a base size the header follows (the xxxLarge step is about 1.35).
    static let scaleCap: CGFloat = 1.35
    /// Height reserved under the face for the pill (name + status line) at the default text size.
    static let pillAreaBase: CGFloat = 44

    static func capped(_ scaled: CGFloat, base: CGFloat) -> CGFloat { min(scaled, base * scaleCap) }
}

/// The header of the main chat and of Feed / Ideas / Goals / Library: the face
/// centred on its disc (its size is the Appearance setting; Hidden keeps the
/// pill only), the name pill, a round button in each top corner.
struct NanoMuseMuseHeader<Leading: View, Trailing: View>: View {
    var mood: NanoMuseMood
    var name: String
    var status: String?
    var statusColor: Color = .secondary
    var onFace: () -> Void
    var onName: (() -> Void)? = nil
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing

    @ObservedObject private var appearance = NanoMuseAppearance.shared
    @ScaledMetric(relativeTo: .subheadline) private var pillArea: CGFloat = NanoMuseHeaderMetrics.pillAreaBase

    var body: some View {
        let disc = appearance.avatarSize.points
        let pillAreaHeight = NanoMuseHeaderMetrics.capped(pillArea, base: NanoMuseHeaderMetrics.pillAreaBase)
        ZStack(alignment: .top) {
            VStack(spacing: 0) {
                if let disc {
                    Button(action: onFace) {
                        NanoMuseFaceView(mood: mood, size: disc)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(AppLocalized("About the agent")))
                } else {
                    Color.clear.frame(height: 10)
                }
                Button(action: onName ?? onFace) {
                    NanoMuseNamePill(name: name, status: status, statusColor: statusColor)
                }
                .buttonStyle(.plain)
                .frame(height: pillAreaHeight, alignment: .top)
                .padding(.top, disc == nil ? 0 : -nmPillOverlap)
                .zIndex(1)
            }
            .frame(maxWidth: .infinity)
            HStack(alignment: .top) {
                leading()
                Spacer(minLength: 0)
                trailing()
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
        }
        .padding(.top, 6)
        .padding(.bottom, 4)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Live header for a conversation

/// The big-face header fed by a conversation: the mood and the status line
/// follow what the chat is doing (Android: rememberNanoMuseStatusLine +
/// rememberAgentMood). Rooms pass the main chat's view model so the face
/// stays alive while the person is on another tab.
struct NanoMuseLiveHeader<Leading: View, Trailing: View>: View {
    @ObservedObject var vm: AIChatViewModel
    var name: String
    var onFace: () -> Void
    var onName: (() -> Void)? = nil
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing

    @ObservedObject private var tracker = SessionActivityTracker.shared
    @ObservedObject private var permissions = OffloadPermissionManager.shared
    @ObservedObject private var gate = ConfigConfirmationGate.shared
    @ObservedObject private var studio = NanoMuseAvatarStudioModel.shared
    @ObservedObject private var avatarFlow = NanoMuseAvatarFlow.shared
    @ObservedObject private var motion = NanoMuseAvatarMotion.shared
    @ObservedObject private var appearance = NanoMuseAppearance.shared
    @ObservedObject private var configStore = ProviderConfigStore.shared
    @StateObject private var moods = NanoMuseMoodModel()

    private var info: SessionActivityTracker.SessionToolInfo? {
        guard let sid = vm.sessionId else { return nil }
        return tracker.sessionToolInfo[sid]
    }

    private var statusLine: String? {
        NanoMuseStatus.line(
            waiting: gate.pending != nil,
            needsApproval: permissions.pendingRequest != nil,
            processing: vm.isProcessing,
            toolName: info?.toolName ?? "",
            toolTitle: info?.toolStatus ?? "",
            request: vm.messages.last(where: { $0.role == .user })?.content,
            studio: avatarFlow.statusLine ?? studio.headerStatus,
            motion: motion.statusLine
        )
    }

    /// Idle, with the Appearance switch on: the model answering, as Android's model rows.
    private var idleLine: String? {
        guard appearance.headerModel else { return nil }
        let line = SessionModelDisplay(store: configStore, draftGroupId: vm.initialGroupId).displayName(for: vm.sessionId)
        return line.isEmpty ? nil : line
    }

    private var mood: NanoMuseMood {
        moods.mood(waiting: permissions.pendingRequest != nil || gate.pending != nil, working: vm.isProcessing || studio.isBusy)
    }

    var body: some View {
        let status = statusLine
        NanoMuseMuseHeader(
            mood: mood,
            name: name,
            status: status ?? idleLine,
            statusColor: status == nil ? .secondary : NanoMuseTones.action,
            onFace: onFace,
            onName: onName,
            leading: leading,
            trailing: trailing
        )
        .nmOnChange(of: vm.isProcessing) { processing in
            guard !processing else { return }
            moods.turnEnded(withError: vm.errorMessage != nil || vm.messages.last?.error != nil)
        }
    }
}

/// The header when no conversation is loaded yet: the face idle, no status.
struct NanoMuseStillHeader<Leading: View, Trailing: View>: View {
    var name: String
    var onFace: () -> Void
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        NanoMuseMuseHeader(mood: .idle, name: name, status: nil, onFace: onFace, leading: leading, trailing: trailing)
    }
}

// MARK: - Top bar for pages

/// Muse's page bar: a centred title, the back arrow on a white disc. Used
/// by the Muse settings pages (the system bar stays hidden under it).
struct NanoMuseTopBar<Trailing: View>: View {
    var title: String
    var onBack: (() -> Void)?
    @ViewBuilder var trailing: () -> Trailing

    init(title: String, onBack: (() -> Void)?, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.onBack = onBack
        self.trailing = trailing
    }

    var body: some View {
        ZStack {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .lineLimit(1)
                .padding(.horizontal, 60)
            HStack {
                if let onBack {
                    NanoMuseRoundButton(symbol: "chevron.left", label: AppLocalized("Back"), size: 40, action: onBack)
                }
                Spacer(minLength: 0)
                trailing()
            }
            .padding(.horizontal, 12)
        }
        .frame(height: 52)
    }
}

extension NanoMuseTopBar where Trailing == EmptyView {
    init(title: String, onBack: (() -> Void)?) {
        self.init(title: title, onBack: onBack) { EmptyView() }
    }
}

// MARK: - Cards and rows

/// A white card with rounded corners; rows inside are separated by hairlines.
struct NanoMuseCard<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            content()
        }
        .background(NanoMuseTones.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(.horizontal, 16)
    }
}

/// The hairline between two rows of a card.
struct NanoMuseRowDivider: View {
    var inset: CGFloat = 16
    var body: some View {
        NanoMuseTones.hairline
            .frame(height: 0.5)
            .padding(.leading, inset)
    }
}

/// One row of a Muse card: a title, an optional value on the right, a chevron.
struct NanoMuseRowLabel: View {
    var title: String
    var value: String? = nil
    var titleColor: Color = .primary
    var chevron: Bool = true

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(titleColor)
                .lineLimit(1)
            Spacer(minLength: 8)
            if let value, !value.isEmpty {
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .layoutPriority(-1)
            }
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }
}

/// A row that pushes a destination.
struct NanoMuseLinkRow<Destination: View>: View {
    var title: String
    var value: String? = nil
    @ViewBuilder var destination: () -> Destination

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            NanoMuseRowLabel(title: title, value: value)
        }
        .buttonStyle(.plain)
    }
}

/// A row that runs an action.
struct NanoMuseActionRow: View {
    var title: String
    var value: String? = nil
    var titleColor: Color = .primary
    var chevron: Bool = true
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            NanoMuseRowLabel(title: title, value: value, titleColor: titleColor, chevron: chevron)
        }
        .buttonStyle(.plain)
    }
}

/// A row with a switch.
struct NanoMuseToggleRow: View {
    var title: String
    var subtitle: String? = nil
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .tint(NanoMuseTones.action)
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }
}

/// The small grey text under a card.
struct NanoMuseCaption: View {
    var text: String
    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 32)
            .padding(.top, 6)
    }
}

/// A page on the Muse canvas: the top bar, then scrolling content. The
/// system navigation bar is hidden under it; pushes from inside still work.
struct NanoMusePage<Content: View, Trailing: View>: View {
    var title: String
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content
    @Environment(\.dismiss) private var dismiss

    init(title: String, @ViewBuilder trailing: @escaping () -> Trailing, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.trailing = trailing
        self.content = content
    }

    var body: some View {
        VStack(spacing: 0) {
            NanoMuseTopBar(title: title, onBack: { dismiss() }, trailing: trailing)
            ScrollView {
                VStack(spacing: 12) {
                    content()
                }
                .padding(.top, 4)
                .padding(.bottom, 32)
            }
        }
        .background(NanoMuseTones.canvas.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}

extension NanoMusePage where Trailing == EmptyView {
    init(title: String, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, trailing: { EmptyView() }, content: content)
    }
}
