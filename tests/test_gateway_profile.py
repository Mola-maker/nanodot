"""Gateway connection stays separate from every active slot and account credential."""

import json
import secrets

import httpx
import pytest

from nanomuse.llm import catalogue
from tests.test_providers_api import _make


def test_gateway_is_a_separate_key_preset():
    preset = catalogue.load().get("nanodot-workers")
    assert preset is not None, "Gateway own-key preset is missing"
    assert preset.protocol == "openai"
    assert preset.base_url == "https://molamaker.si/v1"
    assert preset.auth == ["key"]


def test_connect_route_cannot_reuse_implicit_cloud_token(settings):
    client, service = _make(settings)
    before = service.settings.llm.model_dump()
    with client:
        response = client.post("/api/connections/gateway", json={"api_key": ""})
        assert response.status_code == 400, "Missing project key must be refused explicitly"
        assert service.settings.llm.model_dump() == before


@pytest.mark.asyncio
async def test_real_vault_profiles_preserve_active_settings_restart_and_bad_key(tmp_path):
    from nanomuse.llm.gateway_profile import GatewayProfiles
    from nanomuse.vault.vault import CredentialVault

    vault = CredentialVault(tmp_path / "vault.json")
    account_key, project_key = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    vault.set("CLOUD_TOKEN", account_key)
    old_file = tmp_path / "app-settings.json"
    old = {
        "llm": {"base_url": "https://relay.invalid/v1"},
        "avatar": {"id": "keep"},
        "groups": [{"id": "keep", "selected": "old-model"}],
        "gui": {"model": "old-vision"},
    }
    old_file.write_text(json.dumps(old))
    paths = []
    refused = False

    def reply(request):
        paths.append(str(request.url))
        assert request.headers["authorization"] == "Bearer " + project_key
        if refused:
            return httpx.Response(401, json={"error": {"message": "refused"}})
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "id": "text",
                        "architecture": {
                            "input_modalities": ["text"],
                            "output_modalities": ["text"],
                        },
                    },
                    {
                        "id": "vision",
                        "architecture": {
                            "input_modalities": ["text", "image"],
                            "output_modalities": ["text"],
                        },
                    },
                    {
                        "id": "asr",
                        "architecture": {
                            "input_modalities": ["audio"],
                            "output_modalities": ["text"],
                        },
                    },
                ]
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(reply)) as http:
        store = GatewayProfiles(tmp_path / "gateway-profile.json", vault)
        first = await store.connect(project_key, http)
        restart = GatewayProfiles(store.path, CredentialVault(vault.path))
        assert (await restart.connect(project_key, http))["instance_id"] == first["instance_id"]
        refused = True
        with pytest.raises(ValueError, match="refused"):
            await restart.connect(project_key, http)
        assert restart.load() == first
    assert paths == ["https://molamaker.si/v1/models"] * 3
    assert old_file.read_text() == json.dumps(old)
    assert vault.get("CLOUD_TOKEN") == account_key
    assert vault.get(first["key_ref"]) == project_key
    assert project_key not in vault.path.read_text()
    assert project_key not in store.path.read_text()
    assert first["tts_zh_verified"] is False


@pytest.mark.asyncio
async def test_real_openai_chat_sdk_composes_only_one_version(tmp_path):
    from openai import AsyncOpenAI

    from nanomuse.config import LLMSettings
    from nanomuse.llm.gateway_profile import gateway_base, is_fresh
    from nanomuse.llm.openai_chat import OpenAIChatLLM
    from nanomuse.schema import Message

    assert not is_fresh(False, False, False)
    assert not is_fresh(True, True, False)  # implicit Cloud token, no explicit provider
    paths = []

    def reply(request):
        paths.append(request.url.path)
        return httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=(
                'data: {"id":"local","choices":[{"index":0,"delta":{"content":"fixture"},"finish_reason":null}]}\n\n'
                "data: [DONE]\n\n"
            ),
        )

    for form in ("", "/", "/v1", "/v1/"):
        settings = LLMSettings(
            provider="openai",
            model="text",
            base_url=gateway_base("https://molamaker.si" + form),
            stream=True,
        )
        llm = OpenAIChatLLM(settings)
        await llm.client.close()
        llm.client = AsyncOpenAI(
            api_key=secrets.token_urlsafe(24),
            base_url=settings.base_url,
            http_client=httpx.AsyncClient(transport=httpx.MockTransport(reply)),
        )
        try:
            result = await llm.ask([Message(role="user", content="fixture")])
            assert result.content == "fixture"
        finally:
            await llm.client.close()
    assert paths == ["/v1/chat/completions"] * 4


def test_explicit_gateway_selection_cannot_inherit_old_slot_key(settings):
    client, service = _make(settings)
    service.app.vault.set("LLM_API_KEY", secrets.token_urlsafe(24))
    before = service.settings.llm.model_dump()
    with client:
        response = client.put(
            "/api/connections/llm", json={"provider": "nanodot-workers", "model": "text"}
        )
        assert response.status_code == 400, (
            "A gateway slot must use a connected independent profile"
        )
        assert service.settings.llm.model_dump() == before


@pytest.mark.parametrize("preset", ["nanodot-workers", "custom"])
def test_gateway_model_preview_never_uses_old_slot_credential(settings, monkeypatch, preset):
    client, service = _make(settings)
    called = []
    real_client = httpx.AsyncClient

    def reply(request):
        called.append(request.url.path)
        return httpx.Response(200, json={"data": [{"id": "wrong-origin"}]})

    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: real_client(transport=httpx.MockTransport(reply), **kwargs),
    )
    with client:
        response = client.post(
            "/api/llm/models",
            json={"preset": preset, "base_url": "https://molamaker.si/v1"},
        )
        assert response.status_code == 200
        assert called == [], "No dedicated project key means no gateway request"
        assert response.json()["models"] == []


def test_authenticated_connection_and_explicit_use_preserve_cloud_and_old_vault_key(
    settings, monkeypatch
):
    client, service = _make(settings)
    project_key = secrets.token_urlsafe(24)
    old_key = secrets.token_urlsafe(24)
    service.app.vault.set("LLM_API_KEY", old_key)
    old_settings = service.settings.llm.model_dump()
    real_client = httpx.AsyncClient

    def reply(request):
        assert request.headers["authorization"].startswith("Bearer ")
        assert request.headers["authorization"] != "Bearer " + old_key
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "id": "text",
                        "architecture": {
                            "input_modalities": ["text"],
                            "output_modalities": ["text"],
                        },
                    },
                    {
                        "id": "vision",
                        "architecture": {
                            "input_modalities": ["text", "image"],
                            "output_modalities": ["text"],
                        },
                    },
                    {
                        "id": "asr",
                        "architecture": {
                            "input_modalities": ["audio"],
                            "output_modalities": ["text"],
                        },
                    },
                ]
            },
        )

    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: real_client(transport=httpx.MockTransport(reply), **kwargs),
    )
    with client:
        response = client.post("/api/connections/gateway", json={"api_key": project_key})
        assert response.status_code == 200
        profile = response.json()
        assert service.settings.llm.model_dump() == old_settings
        assert service.app.vault.get("LLM_API_KEY") == old_key
        preview = client.post("/api/llm/models", json={"preset": "nanodot-workers"}).json()
        assert preview["models"] == ["text", "vision"]
        assert preview["vision"] == ["vision"]
        assert preview["voice_models"] == ["asr"]
        selected = client.put(
            "/api/connections/llm", json={"provider": "nanodot-workers", "model": "text"}
        )
        assert selected.status_code == 200
        assert service.settings.llm.endpoint == "https://molamaker.si/v1"
        assert service.settings.llm.vision == "off"
        assert service.app.vault.get("LLM_API_KEY") == old_key
        assert service.connections.data["llm"]["api_key"] == "{{vault:" + profile["key_ref"] + "}}"


@pytest.mark.parametrize("provider", ["nanodot-workers", "openai"])
def test_gui_gateway_cannot_inherit_old_credentials(settings, provider):
    client, service = _make(settings)
    before = dict(service.connections.data.get("gui") or {})
    with client:
        response = client.put(
            "/api/connections/gui",
            json={"provider": provider, "base_url": "https://molamaker.si/v1", "model": "vision"},
        )
        assert response.status_code == 400, "Gateway vision needs an independent connected profile"
        assert (service.connections.data.get("gui") or {}) == before


@pytest.mark.parametrize("slot", ["llm", "gui"])
def test_leaving_gateway_origin_never_carries_project_key(settings, slot):
    client, service = _make(settings)
    service.connections.data[slot] = {
        "provider": "nanodot-workers",
        "model": "text",
        "base_url": "https://molamaker.si/v1",
        "api_key": "{{vault:NANODOT_GATEWAY_OWNED}}",
    }
    with client:
        response = client.put(
            "/api/connections/" + slot,
            json={"provider": "openai", "base_url": "https://relay.invalid/v1", "model": "other"},
        )
        assert response.status_code == 200
        assert service.connections.data[slot]["api_key"] == "", (
            "Project key cannot follow a slot to a different origin"
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("explicit_embedding_key", [False, True])
async def test_actual_embedding_vault_resolution_and_sdk_never_export_project_key(
    settings, explicit_embedding_key
):
    from openai import AsyncOpenAI

    from nanomuse.config import LLMSettings
    from nanomuse.memory.embeddings import Embedder

    _, service = _make(settings)
    project_key, old_embedding_key = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    service.app.vault.set("NANODOT_GATEWAY_OWNED", project_key)
    service.app.vault.set("OLD_EMBEDDING", old_embedding_key)
    settings.llm = LLMSettings(
        provider="nanodot-workers",
        base_url="https://molamaker.si/v1",
        api_key="{{vault:NANODOT_GATEWAY_OWNED}}",
        model="text",
    )
    settings.memory.embedding_base_url = "https://embedding.invalid/v1"
    settings.memory.embedding_api_key = "{{vault:OLD_EMBEDDING}}" if explicit_embedding_key else ""
    resolved = service.app._embedding_key()
    assert resolved == (old_embedding_key if explicit_embedding_key else ""), (
        "Project key must remain at its own origin"
    )
    # Actual constructor must also be safe when used without the app resolver.
    for key in [resolved, None]:
        embedder = Embedder(settings.memory, settings.llm, api_key=key)
        credential = embedder.client.api_key
        if explicit_embedding_key and key is None:
            assert credential == "{{vault:OLD_EMBEDDING}}"
            await embedder.close()
            continue
        assert credential != project_key
        seen = []

        def reply(request, seen=seen):
            seen.append(request.headers["authorization"])
            assert request.url.host == "embedding.invalid"
            assert request.headers["authorization"] != "Bearer " + project_key
            return httpx.Response(
                200,
                json={
                    "data": [{"index": 0, "embedding": [0.1, 0.2]}],
                    "model": "embedding",
                    "usage": {"prompt_tokens": 1, "total_tokens": 1},
                },
            )

        await embedder.client.close()
        embedder.client = AsyncOpenAI(
            api_key=credential,
            base_url=embedder.base_url,
            http_client=httpx.AsyncClient(transport=httpx.MockTransport(reply)),
        )
        try:
            result = await embedder.client.embeddings.create(model="embedding", input="fixture")
            assert result.data[0].embedding == [0.1, 0.2]
            assert seen == ["Bearer " + (old_embedding_key if explicit_embedding_key else "EMPTY")]
        finally:
            await embedder.close()
    assert service.app.vault.get("NANODOT_GATEWAY_OWNED") == project_key
    assert service.app.vault.get("OLD_EMBEDDING") == old_embedding_key


def test_project_embedding_guard_preserves_other_providers_and_exact_own_origin():
    from nanomuse.llm.gateway_profile import may_inherit_project_key

    assert may_inherit_project_key(
        "openai", "{{vault:LLM_API_KEY}}", "https://embedding.invalid/v1"
    )
    for target in ["https://molamaker.si", "https://molamaker.si:443/v1"]:
        assert may_inherit_project_key("nanodot-workers", "", target)
        assert may_inherit_project_key("openai", "{{vault:NANODOT_GATEWAY_OWNED}}", target)
    for target in [
        "http://molamaker.si/v1",
        "https://molamaker.si:444/v1",
        "https://molamaker.si.evil.invalid/v1",
        "https://user@molamaker.si/v1",
    ]:
        assert not may_inherit_project_key("nanodot-workers", "", target)
