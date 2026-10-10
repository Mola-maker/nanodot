"""Nanodot defaults apply only to new identities; update checks fail closed."""

import asyncio
import json
from io import StringIO

import pytest
from rich.console import Console
from typer.testing import CliRunner

from nanomuse import cli
from nanomuse.config import AgentSettings
from nanomuse.nudges import DEFAULT_NUDGES
from nanomuse.server import firstrun, update
from nanomuse.server.api import create_app
from nanomuse.server.service import MuseService, Profile


def test_new_identity_uses_dot(settings):
    assert AgentSettings().name == "Dot"
    service = MuseService(settings)
    assert service.profile.name == "Dot"
    assert service.profile.avatar == "dot-v1"


def test_default_project_link_is_nanodot():
    assert DEFAULT_NUDGES["star"]["url"] == "https://github.com/Mola-maker/nanodot"


def test_explicit_names_and_profile_are_preserved(settings):
    settings.agent.name = "nanoMuse"
    assert MuseService(settings).profile.name == "nanoMuse"
    saved = {
        "name": "Lumi",
        "avatar": "custom-face",
        "color": "#123456",
        "style": "My own SOUL",
        "emoji": "🌙",
        "tone": "formal",
    }
    path = settings.data_dir / "profile.json"
    path.write_text(json.dumps(saved))
    before = path.read_bytes()
    service = MuseService(settings)
    assert all(service.profile.to_dict()[key] == value for key, value in saved.items())
    assert path.read_bytes() == before
    assert Profile.from_dict({"name": "nanoMuse"}).name == "nanoMuse"


def test_disabled_updates_never_open_network_even_when_forced(monkeypatch):
    opened = []

    def forbidden_client(*args, **kwargs):
        opened.append(True)
        raise AssertionError("Disabled updates must not create an HTTP client")

    monkeypatch.setattr(update.httpx, "AsyncClient", forbidden_client)
    check = update.UpdateCheck(True)
    for force in (False, True):
        view = asyncio.run(check.view(force=force))
        assert view["enabled"] is False
        assert view["latest"] is None and view["newer"] is False
        assert not view["url"] and not view["download_url"]
    assert opened == []


def test_profile_without_name_keeps_explicit_config_name(settings):
    settings.agent.name = "Lumi"
    (settings.data_dir / "profile.json").write_text('{"avatar": "custom-face"}')
    assert MuseService(settings).profile.name == "Lumi"


@pytest.mark.parametrize(
    "enabled,repo",
    [
        (False, "https://github.com/Mola-maker/nanodot"),
        (True, None),
        (True, "https://nanodot.invalid"),
    ],
)
def test_incomplete_or_disabled_brand_channel_is_inert(monkeypatch, enabled, repo):
    monkeypatch.setattr(update.brand, "UPDATES_ENABLED", enabled)
    monkeypatch.setattr(update.brand, "UPDATE_REPOSITORY_URL", repo)
    assert update.enabled(True) is False
    assert asyncio.run(update.UpdateCheck().view(force=True))["enabled"] is False


def test_runtime_public_brand_and_custom_banner(settings, monkeypatch):
    assert "Nanodot" in CliRunner().invoke(cli.app, ["--help"]).output
    assert create_app(settings).openapi()["info"]["title"] == "Nanodot"
    settings.agent.name = "Lumi"
    output = StringIO()
    monkeypatch.setattr(cli, "console", Console(file=output, width=160, color_system=None))
    cli._banner(settings)
    assert "Nanodot" in output.getvalue() and "Lumi" in output.getvalue()
    for lang in ("en", "zh-CN"):
        intro = firstrun.intro_lines(lang)
        assert "Dot" in intro[0]
        assert "nanoMuse Cloud" in "\n".join(intro)
        assert "Lumi" in firstrun.intro_lines(lang, "Lumi")[0]
