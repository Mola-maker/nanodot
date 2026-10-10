//
//  NanoMuseAllowanceCard.swift
//  Nanodot
//
//  The ways on when the free allowance is spent, paused by the operator, or today's share is
//  used (contract C11, docs/parity.md item 33): one's own key — the vendors as the relay lists
//  them for the region (`spend.guidance`; the bundled catalogue only when it sent none), the
//  region's first, each saying what it covers; a plan one already pays for (ChatGPT, Claude,
//  Kimi, OpenRouter); an invitation. No vendor is recommended; the text says what each covers.
//  `NanoMuseAllowanceCard` is pinned under the chat header by the shell when a turn was
//  refused (with *Try again*); `NanoMuseWaysList` is the same list on the account page;
//  `NanoMuseAllowanceHeadsUp` is the one line above the composer at 80 %.
//  Android: AllowanceWaysCard.kt.
//

import SwiftUI

/// The vendor (and sign-in) a row opens the sheet on.
struct NanoMuseVendorPick: Identifiable, Equatable {
    var vendor: NanoMuseVendor
    var auth: String?
    var id: String { vendor.id + ":" + (auth ?? "key") }
}

/// The list itself: key vendors, plans, an invitation, the guide.
struct NanoMuseWaysList: View {
    var ways: NanoMuseWays
    var inviteURL: String = ""
    var inviteBonusCny: Double?
    var inviteeBonusCny: Double?
    /// On the account page the code and the link are the next section; elsewhere the link is shared from here.
    var inviteBelow: Bool = false
    /// Where the step-by-step guide is (the refusal's `own_key_docs`, the sheet's, the guidance's, the default).
    var docs: String
    /// The pinned card shows three vendors and a *More* toggle; the account page all of them.
    var compact: Bool = true
    var onPick: (NanoMuseVendorPick) -> Void

    @Environment(\.openURL) private var openURL
    @State private var more = false

    private var chinese: Bool { NanoMuseCatalogue.chinese }
    private var mainland: Bool { NanoMuseRegion.isMainland }
    private var shown: [NanoMuseVendor] { compact && !more ? Array(ways.vendors.prefix(3)) : ways.vendors }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // one's own key
            way(symbol: "key", tint: NanoMuseTones.action, title: AppLocalized("Use your own model key"),
                text: mainland
                    ? AppLocalized("Alibaba Cloud Bailian first: one key covers chat, the screen, pictures and clips, and it signs up accounts from mainland China. Each provider below says what it covers.")
                    : AppLocalized("OpenRouter or OpenAI first: one key, most models, pay as you go. Each provider below says what it covers."))
            ForEach(shown) { v in vendorRow(v) }
            if compact, ways.vendors.count > 3 {
                Button(more ? AppLocalized("Fewer providers") : AppLocalized("More providers")) { more.toggle() }
                    .font(.footnote.weight(.medium)).foregroundStyle(NanoMuseTones.action).buttonStyle(.plain)
            }
            if let url = URL(string: docs), !docs.isEmpty {
                Link(AppLocalized("Step-by-step guide"), destination: url).font(.footnote).foregroundStyle(NanoMuseTones.action)
            }
            // a plan one already pays for
            if !ways.signIns.isEmpty {
                way(symbol: "person.crop.circle.badge.checkmark", tint: Color(red: 0.18, green: 0.62, blue: 0.42), title: AppLocalized("A subscription you already pay for"),
                    text: AppLocalized("A ChatGPT, Claude or Kimi plan can sign in here instead of a key. It covers chat and the screen, not pictures or clips."))
                ForEach(ways.signIns) { s in signInRow(s) }
            }
            // an invitation
            if let bonus = inviteBonusCny, bonus > 0 {
                way(symbol: "gift", tint: Color(red: 0.88, green: 0.47, blue: 0.17),
                    title: String(format: AppLocalized("Invite a friend: +¥%@ for you and +¥%@ for them, for each new person who signs up with your link."), NanoMuseAllowance.money(bonus), NanoMuseAllowance.money(inviteeBonusCny ?? bonus)),
                    text: inviteBelow ? AppLocalized("Your code and link are just below.") : (inviteURL.isEmpty ? AppLocalized("Your code and link are under Settings → nanoMuse Cloud.") : AppLocalized("Your link is ready to share.")))
                if !inviteBelow, !inviteURL.isEmpty, let url = URL(string: inviteURL) {
                    ShareLink(item: url) { Label(AppLocalized("Share the link"), systemImage: "square.and.arrow.up").font(.footnote) }
                        .buttonStyle(.plain).foregroundStyle(NanoMuseTones.action)
                }
            }
        }
    }

    private func way(symbol: String, tint: Color, title: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol).font(.system(size: 15, weight: .medium)).foregroundStyle(tint).frame(width: 18).padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.medium))
                Text(text).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func vendorRow(_ v: NanoMuseVendor) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(v.displayName(chinese: chinese)).font(.footnote.weight(.medium))
                Text(NanoMuseCatalogue.covers(v.capabilities)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 6)
            if let url = URL(string: v.keyURL(mainland: mainland)), !v.keyURL(mainland: mainland).isEmpty {
                Button(AppLocalized("Get a key")) { openURL(url) }
                    .font(.caption.weight(.medium)).buttonStyle(.plain).foregroundStyle(NanoMuseTones.action)
            }
            Button(AppLocalized("Add")) { onPick(NanoMuseVendorPick(vendor: v, auth: nil)) }
                .buttonStyle(.borderedProminent).tint(NanoMuseTones.action).foregroundStyle(NanoMuseTones.onAction).controlSize(.mini)
        }
        .padding(.leading, 28)
    }

    private func signInRow(_ s: NanoMuseWays.SignIn) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(s.name).font(.footnote.weight(.medium))
                    Text(NanoMuseCatalogue.covers(s.covers)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 6)
                Button(AppLocalized("Sign in")) { onPick(NanoMuseVendorPick(vendor: s.vendor, auth: s.auth)) }
                    .buttonStyle(.borderedProminent).tint(NanoMuseTones.action).foregroundStyle(NanoMuseTones.onAction).controlSize(.mini)
            }
            if s.auth == NanoMuseCatalogue.authChatGPT {
                // the honest line about the ChatGPT sign-in: the relay's when it sent one, else ours
                Text(ways.chatgptCaveat.isEmpty
                     ? AppLocalized("OpenAI's terms cover using a ChatGPT plan inside OpenAI's own Codex; other apps have had this access cut off before (OpenCode, January 2026). If it stops working, an API key does.")
                     : ways.chatgptCaveat)
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .padding(.leading, 28)
    }
}

/// Pinned under the chat header when a turn was refused for the allowance: the lead (used up
/// / paused / today's share), what stays, the ways on, *Try again* and a way to put it away.
struct NanoMuseAllowanceCard: View {
    let refused: NanoMuseAllowance.Refused
    var onTryAgain: () -> Void
    var onDismiss: () -> Void

    @State private var pick: NanoMuseVendorPick?

    private var ways: NanoMuseWays {
        NanoMuseWays.resolve(guidance: refused.facts.guidance ?? NanoMuseAllowance.storedGuidance(), catalogue: NanoMuseCatalogue.bundled, mainland: NanoMuseRegion.isMainland, chinese: NanoMuseCatalogue.chinese)
    }

    private var title: String {
        if refused.paused { return AppLocalized("The free allowance is paused on this relay for now. It is not used up.") }
        if refused.dailyCap { return AppLocalized("Today's share of the free allowance is used up.") }
        return AppLocalized("The free allowance is used up.")
    }

    private var subtitle: String {
        if refused.paused { return AppLocalized("What is left stays as it is; the ways below work now.") }
        if refused.dailyCap { return AppLocalized("It resets tomorrow. The ways below work now; your sign-in and your devices keep working either way.") }
        return AppLocalized("Two ways on; your sign-in and your devices keep working either way.")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: refused.paused ? "pause.circle" : "gauge").font(.system(size: 16, weight: .medium)).foregroundStyle(Color(red: 0.49, green: 0.36, blue: 1.0)).padding(.top, 2)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.subheadline.weight(.semibold))
                    Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                Button(action: onDismiss) {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .semibold)).foregroundStyle(.secondary).padding(4)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(AppLocalized("Not now")))
            }
            NanoMuseWaysList(
                ways: ways,
                inviteURL: refused.facts.inviteURL,
                inviteBonusCny: refused.facts.inviteBonusCny,
                inviteeBonusCny: refused.facts.inviteeBonusCny,
                docs: refused.facts.ownKeyDocs.isEmpty ? (ways.docs.isEmpty ? NanoMuseLinks.ownKeyDocs : ways.docs) : refused.facts.ownKeyDocs,
                compact: true
            ) { pick = $0 }
            HStack(spacing: 10) {
                Button(AppLocalized("Try again"), action: onTryAgain)
                    .buttonStyle(.borderedProminent).tint(NanoMuseTones.action).foregroundStyle(NanoMuseTones.onAction).controlSize(.small)
                Button(AppLocalized("Open Settings")) { NotificationCenter.default.post(name: .nanoMuseHomeAction, object: "settings") }
                    .buttonStyle(.plain).font(.footnote).foregroundStyle(NanoMuseTones.action)
            }
        }
        .sheet(item: $pick) { p in
            NanoMuseVendorSheet(vendor: p.vendor, signIn: p.auth) { inst in
                if inst != nil { onDismiss() }
            }
        }
    }
}

/// The one line above the composer at 80 % of the pool (`spend.warn`), once per pool size.
struct NanoMuseAllowanceHeadsUp: View {
    var text: String
    var onHide: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "gauge").font(.system(size: 13, weight: .medium)).foregroundStyle(Color(red: 0.88, green: 0.47, blue: 0.17)).padding(.top, 2)
            Text(text).font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 4)
            Button(action: onHide) {
                Image(systemName: "xmark").font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary).padding(3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(AppLocalized("Not now")))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(NanoMuseTones.fill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
