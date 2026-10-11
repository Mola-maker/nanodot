import Foundation

/// A project-key provider kept separate from Cloud accounts and existing selections.
enum NanodotGateway {
    static let presetID = "nanodot-workers"
    static let origin = "https://molamaker.si"
    static let label = "Nanodot · Workers AI"
    private static let ownershipKey = "nanodot.gateway.instanceID"
    @MainActor private static var connecting = false

    static func isFresh(configLoaded: Bool, hasAccount: Bool, hasProvider: Bool) -> Bool {
        configLoaded && !hasAccount && !hasProvider
    }

    static func newInstance() -> ProviderInstance {
        ProviderInstance(label: label, providerType: .openAI, credentialType: .apiKey,
                         customBaseURL: origin, appendV1Suffix: true, azureMode: false)
    }

    static func ownedInstance(id: String?, instances: [ProviderInstance]) -> ProviderInstance? {
        guard let id else { return nil }
        return instances.first {
            $0.id == id && $0.providerType == .openAI && $0.credentialType == .apiKey &&
            $0.customBaseURL == origin && $0.appendV1Suffix && !$0.azureMode
        }
    }

    @MainActor
    @discardableResult
    static func connect(credential: String) async throws -> ProviderInstance {
        let key = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, !key.contains(where: { $0.isWhitespace }) else {
            throw NSError(domain: "NanodotGateway", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: AppLocalized("A dedicated project key is required.")])
        }
        let store = ProviderConfigStore.shared
        while !store.gatewayConfigLoaded || connecting {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        connecting = true
        defer { connecting = false }
        let defaults = UserDefaults.standard
        let previous = ownedInstance(id: defaults.string(forKey: ownershipKey), instances: store.instances)
        let instance = previous ?? newInstance()
        // Validate with the actual models API before touching Keychain or cached entries.
        let models = try await OpenAIModelsAPI.fetchModels(apiKey: key, baseURL: origin,
                                                          appendV1Suffix: true, forceRefresh: true)
        guard !models.isEmpty else {
            throw NSError(domain: "NanodotGateway", code: 400,
                          userInfo: [NSLocalizedDescriptionKey: AppLocalized("The gateway did not list its models; existing models were kept.")])
        }
        ProviderKeychainHelper.saveAPIKey(key, instanceId: instance.id)
        guard ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) == key else {
            throw NSError(domain: "NanodotGateway", code: 500,
                          userInfo: [NSLocalizedDescriptionKey: AppLocalized("Could not save the project key.")])
        }
        if previous == nil { store.addInstance(instance) }
        defaults.set(instance.id, forKey: ownershipKey)
        store.replaceEntries(for: instance.id, models: models, caller: "NanodotGateway.connect")
        // No group creation, model-selection migration, account or avatar updates.
        return instance
    }
}
