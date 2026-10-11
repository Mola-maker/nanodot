//
//  NanoMuseVendorSheet.swift
//  Nanodot
//
//  A provider of one's own from the catalogue (contract C11, docs/parity.md item 32): the
//  sheet opens on one vendor — paste its key (*Get a key* opens the vendor's key page), or
//  sign in with a plan one already pays for (ChatGPT through upstream's Codex OAuth, Claude,
//  OpenRouter, Kimi's device code) — and the provider is added with the vendor's endpoint,
//  its models fetched, a group named after it made the chat default when the person had
//  none. Every vendor of `providers.json` (or of the relay's guidance) goes through here;
//  the two-preset `NanoMuseOwnKeySheet` is what it replaces. Android: OwnKeyPresets +
//  upstream's AddProviderScreen with `?preset=`.
//

import SwiftUI

/// Adding a catalogue vendor to the phone: the instance, the key or the sign-in, the defaults.
@MainActor
enum NanoMuseVendorSetup {
    /// `https://api.deepseek.com/v1` → (`https://api.deepseek.com`, true): upstream keeps the
    /// `/v1` as a switch beside the base.
    static func split(_ endpoint: String) -> (base: String, appendV1: Bool) {
        var e = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        while e.hasSuffix("/") { e.removeLast() }
        if e.lowercased().hasSuffix("/v1") { return (String(e.dropLast(3)), true) }
        return (e, false)
    }

    /// The upstream type a sign-in runs under: Kimi's device code has a type of its own; the others their vendor's.
    static func providerType(_ vendor: NanoMuseVendor, auth: String?) -> ProviderType {
        if auth == NanoMuseCatalogue.authKimi { return .kimiCode }
        if auth == NanoMuseCatalogue.authOpenRouter { return .openRouter }
        return vendor.providerType
    }

    /// The instance of this vendor already on the phone, if any: by the endpoint's host for an
    /// OpenAI-shaped one, by the type for upstream's own (OpenRouter, Anthropic, Gemini, xAI,
    /// Kimi), never the relay's.
    static func existingInstance(_ vendor: NanoMuseVendor, auth: String?, mainland: Bool) -> ProviderInstance? {
        let cloudId = NanoMuseCloud.instance?.id
        let type = providerType(vendor, auth: auth)
        let credential: ProviderCredential = auth == nil ? .apiKey : .oauth
        let hosts = Set([vendor.baseURL, vendor.baseURLGlobal ?? ""].compactMap(NanoMuseProxy.hostOf))
        return ProviderConfigStore.shared.instances.first { inst in
            guard inst.id != cloudId, inst.providerType == type, inst.credentialType == credential else { return false }
            if type == .openAI && auth == nil {
                guard let host = NanoMuseProxy.hostOf(inst.customBaseURL ?? "") else { return false }
                return hosts.contains(host)
            }
            return true
        }
    }

    /// The vendor answered the key with 401 or 403: the key is not kept, the sheet says why.
    struct Refused: LocalizedError {
        let status: Int
        let vendorMessage: String
        var errorDescription: String? {
            NanoMuseMediaWords.refused(status: status, vendorMessage: vendorMessage) + " " + AppLocalized("Check the key and paste it again.")
        }
    }

    /// Add the vendor with a pasted key, fetch its models, make the chat default the group's first.
    ///
    /// The key is tried against the vendor's model list before anything is kept: a 401 or 403
    /// throws `Refused` and leaves the phone as it was (an instance that already existed keeps
    /// its previous key). Upstream's `refreshModels` would have fallen back to a catalogue list
    /// and the wrong key would have gone unnoticed until the first chat.
    @discardableResult
    static func install(_ vendor: NanoMuseVendor, apiKey: String, mainland: Bool, chinese: Bool) async throws -> ProviderInstance? {
        if vendor.id == NanodotGateway.presetID {
            return try await NanodotGateway.connect(credential: apiKey)
        }
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return nil }
        let store = ProviderConfigStore.shared
        let existing = existingInstance(vendor, auth: nil, mainland: mainland)
        let inst: ProviderInstance = existing ?? {
            let (base, v1) = split(vendor.baseURL(mainland: mainland))
            return ProviderInstance(label: vendor.displayName(chinese: chinese), providerType: vendor.providerType, credentialType: .apiKey, customBaseURL: base.isEmpty ? nil : base, appendV1Suffix: v1)
        }()
        let previousKey = existing.flatMap { ProviderKeychainHelper.loadAPIKey(instanceId: $0.id) }
        ProviderKeychainHelper.saveAPIKey(key, instanceId: inst.id)
        var models: [LLMModel] = []
        do {
            models = try await ProviderConfigStore.fetchModelsForInstance(inst, forceRefresh: true)
        } catch {
            if let refusal = refusal(error) {
                if let previousKey { ProviderKeychainHelper.saveAPIKey(previousKey, instanceId: inst.id) } else { ProviderKeychainHelper.deleteAPIKey(instanceId: inst.id) }
                throw refusal
            }
            // Anything else (no /models on this host, a network hiccup): upstream's fallback below.
        }
        if existing == nil { store.addInstance(inst) }
        if models.isEmpty {
            await store.refreshModels(for: inst)
        } else {
            store.replaceEntries(for: inst.id, models: models, caller: "NanoMuseVendorSetup.install")
        }
        adoptDefaults(inst, vendor: vendor)
        return inst
    }

    /// 401 / 403 from the models fetch, as upstream's APIs report them: `LLMError.invalidAPIKey`
    /// ("OpenAI HTTP 401: {…}") or, for Anthropic, `providerError` ("Failed to fetch models (401): …").
    static func refusal(_ error: Error) -> Refused? {
        guard let llm = error as? LLMError else { return nil }
        let text: String
        switch llm {
        case .invalidAPIKey(let detail): text = detail.isEmpty ? "HTTP 401" : detail
        case .providerError(let message): text = message
        default: return nil
        }
        guard let r = text.range(of: "(HTTP |\\()(401|403)", options: .regularExpression) else {
            if case .invalidAPIKey = llm { return Refused(status: 401, vendorMessage: NanoMuseProviderReach.vendorMessage(text)) }
            return nil
        }
        let status = Int(text[r].suffix(3)) ?? 401
        return Refused(status: status, vendorMessage: NanoMuseProviderReach.vendorMessage(text))
    }

    /// A server on a computer of one's own (Ollama, LM Studio, vLLM): no key, the address the
    /// person typed, the models fetched from it.
    @discardableResult
    static func installLocal(_ vendor: NanoMuseVendor, address: String, chinese: Bool) async -> ProviderInstance? {
        let (base, v1) = split(address)
        guard !base.isEmpty, URL(string: base)?.host != nil else { return nil }
        let store = ProviderConfigStore.shared
        let inst = ProviderInstance(label: vendor.displayName(chinese: chinese), providerType: .openAI, credentialType: .apiKey, customBaseURL: base, appendV1Suffix: v1)
        store.addInstance(inst)
        await store.refreshModels(for: inst)
        adoptDefaults(inst, vendor: vendor)
        return inst
    }

    /// A sign-in that runs in a browser sheet (ChatGPT, Claude, OpenRouter): upstream's OAuth
    /// manager leaves the token in the keychain under the instance id, then the instance is
    /// added as upstream's AddProviderView does. Kimi's device code is a sheet of its own
    /// (`KimiDeviceLoginSheet`); `finishSignIn` follows it.
    @discardableResult
    static func signIn(_ vendor: NanoMuseVendor, auth: String, mainland: Bool, chinese: Bool) async throws -> ProviderInstance? {
        let store = ProviderConfigStore.shared
        let existing = existingInstance(vendor, auth: auth, mainland: mainland)
        let id = existing?.id ?? UUID().uuidString
        switch auth {
        case NanoMuseCatalogue.authChatGPT: try await CodexOAuthManager.shared.login(instanceId: id)
        case NanoMuseCatalogue.authClaude: try await ClaudeOAuthManager.shared.login(instanceId: id)
        case NanoMuseCatalogue.authOpenRouter: try await OpenRouterOAuthManager.shared.login(instanceId: id)
        default: return nil
        }
        let inst = existing ?? {
            let fresh = ProviderInstance(id: id, label: NanoMuseCatalogue.planName(auth: auth, vendor: vendor, chinese: chinese), providerType: providerType(vendor, auth: auth), credentialType: .oauth)
            store.addInstance(fresh)
            return fresh
        }()
        await store.refreshModels(for: inst)
        adoptDefaults(inst, vendor: vendor)
        return inst
    }

    /// After Kimi's device code succeeded for `id`: the instance, its models, the defaults.
    @discardableResult
    static func finishSignIn(_ vendor: NanoMuseVendor, auth: String, instanceId id: String, mainland: Bool, chinese: Bool) async -> ProviderInstance? {
        let store = ProviderConfigStore.shared
        let inst = store.instances.first { $0.id == id } ?? {
            let fresh = ProviderInstance(id: id, label: NanoMuseCatalogue.planName(auth: auth, vendor: vendor, chinese: chinese), providerType: providerType(vendor, auth: auth), credentialType: .oauth)
            store.addInstance(fresh)
            return fresh
        }()
        await store.refreshModels(for: inst)
        adoptDefaults(inst, vendor: vendor)
        return inst
    }

    /// A group named after the vendor with its chat default first (only if none of ours has
    /// that model), so the group picker and the per-chat picker list the provider. The
    /// default group is set only when the person had none at all: which slots the new key
    /// takes over is the "Use it for" card's question (0.1.41), not a side effect of saving.
    private static func adoptDefaults(_ inst: ProviderInstance, vendor: NanoMuseVendor) {
        let store = ProviderConfigStore.shared
        let entries = store.entries(for: inst.id).filter { !$0.isHidden }
        let wanted = vendor.defaults["chat"] ?? ""
        let chat = entries.first { $0.model.id == wanted }
            ?? entries.first { !wanted.isEmpty && $0.model.id.hasSuffix("/" + wanted) }
            ?? entries.first { $0.model.id.lowercased().contains("deepseek") && NanoMuseVision.deepSeekSees($0.model.id.lowercased()) == true }
            ?? entries.first { NanoMuseModelSlots.chats($0) }
        if let chat, !store.modelGroups.contains(where: { $0.memberEntryIds.contains(chat.id) }) {
            let group = ModelGroup(name: inst.label, memberEntryIds: [chat.id])
            store.addGroup(group)
            if store.defaultPrimaryGroupId == nil { store.defaultPrimaryGroupId = group.id }
        }
    }
}

/// One vendor: its key, or its sign-in. Opened from the ways-on card and the account page.
struct NanoMuseVendorSheet: View {
    let vendor: NanoMuseVendor
    /// The sign-in to lead with (`oauth-chatgpt` …), or nil for the key.
    var signIn: String? = nil
    var onDone: (ProviderInstance?) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var key = ""
    @State private var address = ""
    @State private var busy = false
    @State private var message: String?
    @State private var kimi = false
    @State private var kimiInstanceId = UUID().uuidString
    /// The provider just saved: the form gives way to the "Use it for" card (0.1.41).
    @State private var saved: ProviderInstance?

    private var mainland: Bool { NanoMuseRegion.isMainland }
    private var chinese: Bool { NanoMuseCatalogue.chinese }
    private var name: String { vendor.displayName(chinese: chinese) }
    private var auth: String? { signIn ?? vendor.signIn }

    var body: some View {
        NavigationStack {
            Group {
                if let saved {
                    NanoMuseUseItForView(instance: saved) {
                        onDone(saved)
                        dismiss()
                    }
                } else {
                    form
                }
            }
            .sheet(isPresented: $kimi) {
                KimiDeviceLoginSheet(instanceId: kimiInstanceId) { success in
                    guard success else { return }
                    Task {
                        let inst = await NanoMuseVendorSetup.finishSignIn(vendor, auth: NanoMuseCatalogue.authKimi, instanceId: kimiInstanceId, mainland: mainland, chinese: chinese)
                        finish(inst)
                    }
                }
            }
        }
    }

    /// After a save: the "Use it for" card when the provider can take a slot, else straight out.
    private func finish(_ inst: ProviderInstance?) {
        if let inst, NanoMuseUseItForView.worthAsking(inst) {
            saved = inst
        } else {
            onDone(inst)
            dismiss()
        }
    }

    private var form: some View {
        Form {
            if let auth {
                Section {
                    Button {
                        if auth == NanoMuseCatalogue.authKimi { kimiInstanceId = UUID().uuidString; kimi = true } else { Task { await runSignIn(auth) } }
                    } label: {
                        HStack {
                            Label(String(format: AppLocalized("Sign in with %@"), NanoMuseCatalogue.planName(auth: auth, vendor: vendor, chinese: chinese)), systemImage: "person.crop.circle.badge.checkmark")
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy)
                } header: {
                    Text(AppLocalized("A plan you already pay for"))
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(String(format: AppLocalized("Covers %@."), NanoMuseCatalogue.covers(vendor.capabilitiesFor(auth))))
                        if auth == NanoMuseCatalogue.authChatGPT {
                            Text(NanoMuseAllowance.storedGuidance()?.caveat(chinese: chinese).nilIfEmpty ?? AppLocalized("OpenAI's terms cover using a ChatGPT plan inside OpenAI's own Codex; other apps have had this access cut off before (OpenCode, January 2026). If it stops working, an API key does."))
                        }
                    }
                }
            }
            if vendor.local {
                Section {
                    TextField(AppLocalized("Address"), text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .disabled(busy)
                    Button {
                        Task { await saveLocal() }
                    } label: {
                        HStack {
                            Text(AppLocalized("Use this server"))
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy || address.trimmingCharacters(in: .whitespaces).isEmpty)
                } header: {
                    Text(AppLocalized("On a computer of your own"))
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        if let message { Text(message).foregroundStyle(.red) }
                        Text(AppLocalized("The phone must reach the computer: the same Wi-Fi, or a tunnel. Nothing leaves your network."))
                        let note = vendor.id == NanodotGateway.presetID ? AppLocalized("Requires a dedicated project key. Existing settings and selected models are kept. Live service acceptance is pending.") : vendor.note(chinese: chinese)
                        if !note.isEmpty { Text(note) }
                    }
                }
            }
            if vendor.takesKey {
                Section {
                    SecureField(vendor.keyHint.map { String(format: AppLocalized("Paste the API key (%@)"), $0) } ?? AppLocalized("Paste the API key"), text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(busy)
                    Button {
                        Task { await save() }
                    } label: {
                        HStack {
                            Text(AppLocalized("Use this key"))
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy || key.trimmingCharacters(in: .whitespaces).isEmpty)
                    if let url = URL(string: vendor.keyURL(mainland: mainland)), !vendor.keyURL(mainland: mainland).isEmpty {
                        Button {
                            openURL(url)
                        } label: {
                            Label(AppLocalized("Get a key"), systemImage: "arrow.up.right.square")
                        }
                    }
                } header: {
                    Text(AppLocalized("A key of your own"))
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        if let message {
                            Text(message).foregroundStyle(.red)
                        } else {
                            Text(String(format: AppLocalized("The key stays on this phone and is sent only to %@. Covers %@."), NanoMuseProxy.hostOf(vendor.baseURL(mainland: mainland)) ?? name, NanoMuseCatalogue.covers(vendor.capabilities)))
                        }
                        let note = vendor.id == NanodotGateway.presetID ? AppLocalized("Requires a dedicated project key. Existing settings and selected models are kept. Live service acceptance is pending.") : vendor.note(chinese: chinese)
                        if !note.isEmpty { Text(note) }
                    }
                }
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if address.isEmpty { address = vendor.baseURL } }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(AppLocalized("Cancel")) { dismiss() } }
        }
    }

    private func save() async {
        busy = true
        defer { busy = false }
        message = nil
        do {
            guard let inst = try await NanoMuseVendorSetup.install(vendor, apiKey: key, mainland: mainland, chinese: chinese) else {
                message = AppLocalized("Nothing to add")
                return
            }
            finish(inst)
        } catch {
            // A refused key: the sheet stays open with the vendor's answer under the field.
            message = error.localizedDescription
        }
    }

    private func saveLocal() async {
        busy = true
        defer { busy = false }
        guard let inst = await NanoMuseVendorSetup.installLocal(vendor, address: address, chinese: chinese) else {
            message = AppLocalized("That is not an address the phone can reach.")
            return
        }
        finish(inst)
    }

    private func runSignIn(_ auth: String) async {
        busy = true
        defer { busy = false }
        do {
            let inst = try await NanoMuseVendorSetup.signIn(vendor, auth: auth, mainland: mainland, chinese: chinese)
            finish(inst)
        } catch {
            message = error.localizedDescription
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
