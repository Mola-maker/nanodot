import XCTest
@testable import Minis

/// XCTest execution requires a macOS runner with compatible native dependencies.
@MainActor
final class NanodotGatewayTests: XCTestCase {
    func testUnloadedAndImplicitCloudAccountAreNeverFresh() {
        for loaded in [false, true] {
            for account in [false, true] {
                for provider in [false, true] {
                    XCTAssertEqual(NanodotGateway.isFresh(configLoaded: loaded, hasAccount: account, hasProvider: provider), loaded && !account && !provider)
                }
            }
        }
    }

    func testGatewayIdentityCannotAdoptAnOldCustomProvider() {
        let old = ProviderInstance(label: "Existing", providerType: .openAI, credentialType: .apiKey, customBaseURL: "https://molamaker.si", appendV1Suffix: true)
        let gateway = NanodotGateway.newInstance()
        XCTAssertNotEqual(old.id, gateway.id)
        XCTAssertEqual(gateway.customBaseURL, "https://molamaker.si")
        XCTAssertEqual(gateway.providerType, .openAI)
        XCTAssertFalse(gateway.azureMode)
        XCTAssertEqual(gateway.resolvedBaseURL(default: "https://api.openai.com").url, "https://molamaker.si")
        XCTAssertNil(NanodotGateway.ownedInstance(id: nil, instances: [old]))
        XCTAssertEqual(NanodotGateway.ownedInstance(id: gateway.id, instances: [old, gateway])?.id, gateway.id)
        XCTAssertNil(NanodotGateway.ownedInstance(id: old.id, instances: [ProviderInstance(id: old.id, label: "Changed", providerType: .openAI, credentialType: .apiKey, customBaseURL: "https://relay.invalid")]))
    }

    func testActualVoiceRequestBuildersNormalizeAllVersionForms() throws {
        for base in ["https://molamaker.si", "https://molamaker.si/", "https://molamaker.si/v1", "https://molamaker.si/v1/"] {
            let provider = VoiceProvider(providerId: UUID().uuidString, baseURL: base)
            let input = try provider.buildVoiceInputRequest(VoiceInputRequest(audioData: Data(repeating: 0, count: 44), model: "@cf/openai/whisper-large-v3-turbo"))
            XCTAssertEqual(input.url?.path, "/v1/audio/transcriptions")
            let output = try provider.buildVoiceOutputRequest(VoiceOutputRequest(input: "你好", model: "@cf/myshell-ai/melotts"))
            XCTAssertEqual(output.url?.path, "/v1/audio/speech")
        }
    }
    func testActualModelsAndChatStreamNormalizeEveryBaseForm() async throws {
        URLProtocol.registerClass(NanodotGatewayFixtureProtocol.self)
        defer { URLProtocol.unregisterClass(NanodotGatewayFixtureProtocol.self) }
        NanodotGatewayFixtureProtocol.reset()
        for form in ["", "/", "/v1", "/v1/"] {
            let base = "https://molamaker.si" + form
            let key = UUID().uuidString
            let models = try await OpenAIModelsAPI.fetchModels(apiKey: key, baseURL: base, forceRefresh: true)
            XCTAssertEqual(models.count, 3)
            XCTAssertEqual(models.first { $0.id == "@cf/qwen/qwen3-30b-a3b-fp8" }?.modalityOverride, [.textInput, .textOutput])
            XCTAssertEqual(models.first { $0.id == "@cf/google/gemma-4-26b-a4b-it" }?.modalityOverride, [.textInput, .imageInput, .textOutput])
            let provider = OpenAIProvider(apiKey: key, model: LLMModel(id: "@cf/qwen/qwen3-30b-a3b-fp8", displayName: "Qwen", provider: "Custom"), customBaseURL: base)
            let stream = try await provider.streamMessage(messages: [LLMMessage(role: .user, content: "fixture")], systemPrompt: nil, maxTokens: 32, temperature: nil)
            var answer = ""
            for try await chunk in stream { if case .text(let text) = chunk { answer += text } }
            XCTAssertEqual(answer, "fixture")
        }
        XCTAssertEqual(NanodotGatewayFixtureProtocol.paths(), Array(repeating: ["/v1/models", "/v1/chat/completions"], count: 4).flatMap { $0 })
    }

    func testActualStoreConnectPreservesOldOriginsGroupsAndKeysAcrossReconnectAndRefusal() async throws {
        URLProtocol.registerClass(NanodotGatewayFixtureProtocol.self)
        defer { URLProtocol.unregisterClass(NanodotGatewayFixtureProtocol.self) }
        NanodotGatewayFixtureProtocol.reset()
        let store = ProviderConfigStore.shared
        for _ in 0..<500 {
            if store.gatewayConfigLoaded { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        guard store.gatewayConfigLoaded else { XCTFail("Provider database did not become ready"); return }
        let defaults = UserDefaults.standard
        let priorOwnership = defaults.string(forKey: "nanodot.gateway.instanceID")
        defaults.removeObject(forKey: "nanodot.gateway.instanceID")
        let old = ProviderInstance(label: "Fixture existing", providerType: .openAI, credentialType: .apiKey, customBaseURL: "https://relay.invalid")
        let oldKey = UUID().uuidString
        ProviderKeychainHelper.saveAPIKey(oldKey, instanceId: old.id)
        store.addInstance(old)
        let before = store.config
        var created: String?
        defer {
            if let created { store.removeInstance(created) }
            store.removeInstance(old.id)
            ProviderKeychainHelper.deleteAPIKey(instanceId: old.id)
            if let created { ProviderKeychainHelper.deleteAPIKey(instanceId: created) }
            if let priorOwnership { defaults.set(priorOwnership, forKey: "nanodot.gateway.instanceID") }
            else { defaults.removeObject(forKey: "nanodot.gateway.instanceID") }
        }
        let key = UUID().uuidString
        let first = try await NanodotGateway.connect(credential: key)
        created = first.id
        let again = try await NanodotGateway.connect(credential: key)
        XCTAssertEqual(first.id, again.id)
        XCTAssertEqual(store.instances.filter { $0.id != first.id }, before.instances)
        XCTAssertEqual(store.config.modelGroups, before.modelGroups)
        XCTAssertEqual(store.defaultPrimaryGroupId, before.defaultPrimaryGroupId)
        XCTAssertEqual(ProviderKeychainHelper.loadAPIKey(instanceId: old.id), oldKey)
        let entries = store.config.modelEntries.filter { $0.providerInstanceId == first.id }
        NanodotGatewayFixtureProtocol.refuse()
        do { _ = try await NanodotGateway.connect(credential: UUID().uuidString); XCTFail("Invalid project key must fail") }
        catch { }
        XCTAssertEqual(store.config.modelEntries.filter { $0.providerInstanceId == first.id }, entries)
        XCTAssertEqual(ProviderKeychainHelper.loadAPIKey(instanceId: first.id), key)
        XCTAssertEqual(NanodotGateway.ownedInstance(id: defaults.string(forKey: "nanodot.gateway.instanceID"), instances: store.instances)?.id, first.id)
        NanodotGatewayFixtureProtocol.reset()
        var edited = first
        edited.customBaseURL = "https://foreign.invalid"
        store.updateInstance(edited)
        _ = try await ProviderConfigStore.fetchModelsForInstance(store.instances.first { $0.id == first.id }!, forceRefresh: true)
        do { _ = try await ProviderConfigStore.fetchModelsForInstance(edited, forceRefresh: true); XCTFail("Stale off-origin editor instance must be refused") }
        catch { }
        XCTAssertEqual(store.instances.first { $0.id == first.id }?.customBaseURL, NanodotGateway.origin)
        XCTAssertFalse(NanodotGatewayFixtureProtocol.authorizations(host: "foreign.invalid").contains("Bearer " + key))
        var generic = old
        generic.customBaseURL = "https://generic.invalid"
        store.updateInstance(generic)
        _ = try await ProviderConfigStore.fetchModelsForInstance(generic, forceRefresh: true)
        XCTAssertTrue(NanodotGatewayFixtureProtocol.authorizations(host: "generic.invalid").contains("Bearer " + oldKey))
    }

}


/// Test-only transport intercepts all requests so fixture tests cannot reach an external API.
private final class NanodotGatewayFixtureProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private nonisolated(unsafe) static var captured: [String] = []
    private nonisolated(unsafe) static var denied = false
    private nonisolated(unsafe) static var authorization: [(String, String)] = []
    static func authorizations(host: String) -> [String] { lock.lock(); defer { lock.unlock() }; return authorization.filter { $0.0 == host }.map { $0.1 } }
    static func reset() { lock.lock(); defer { lock.unlock() }; captured = []; denied = false; authorization = [] }
    static func paths() -> [String] { lock.lock(); defer { lock.unlock() }; return captured }
    static func refuse() { lock.lock(); defer { lock.unlock() }; denied = true }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        Self.lock.lock()
        if url.host == "molamaker.si" { Self.captured.append(url.path) }
        Self.authorization.append((url.host ?? "", request.value(forHTTPHeaderField: "Authorization") ?? ""))
        let denied = Self.denied
        Self.lock.unlock()
        let status = denied ? 401 : 200
        let models = """
        {"data":[{"id":"@cf/qwen/qwen3-30b-a3b-fp8","architecture":{"input_modalities":["text"],"output_modalities":["text"]}},{"id":"@cf/google/gemma-4-26b-a4b-it","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},{"id":"@cf/openai/whisper-large-v3-turbo","architecture":{"input_modalities":["audio"],"output_modalities":["text"]}}]}
        """
        let stream = "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"fixture\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
        let body = denied ? "{\"error\":{\"message\":\"refused\"}}" : (url.path.hasSuffix("/models") ? models : stream)
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": url.path.hasSuffix("/models") ? "application/json" : "text/event-stream"]) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
