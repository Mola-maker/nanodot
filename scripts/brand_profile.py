"""One validated, immutable identity contract shared by branding generators."""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Literal, cast
from urllib.parse import urlparse


@dataclass(frozen=True)
class UpdateConfig:
    enabled: bool
    repositoryUrl: str | None


@dataclass(frozen=True)
class BrandProfile:
    schemaVersion: int
    displayName: str
    agentName: str
    androidApplicationId: str
    externalScheme: str
    defaultAvatar: str
    upstreamSourceUrl: str
    theme: Literal["system", "light", "dark"]
    updates: UpdateConfig
    repositoryUrl: str
    preserveNames: tuple[str, ...]
    themeColors: Mapping[str, Mapping[str, str]]


def _text(data: dict[str, object], key: str) -> str:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip() or value != value.strip():
        raise ValueError(f"{key} must be a non-empty string without surrounding whitespace")
    if any(ord(c) < 32 for c in value):
        raise ValueError(f"{key} contains control characters")
    return value


def _https(value: str, key: str, *, repository: bool = False) -> str:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{key} must be a public HTTPS URL")
    if repository and (
        parsed.netloc != "github.com"
        or not re.fullmatch(r"/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?", parsed.path)
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(f"{key} must identify a GitHub owner/repository")
    return value.rstrip("/")


def load_brand(path: Path) -> BrandProfile:
    """Reject incomplete or invalid profiles before a generator changes the tree."""
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("brand profile must be a JSON object")
    if type(data.get("schemaVersion")) is not int or data["schemaVersion"] != 1:
        raise ValueError("unsupported schemaVersion (expected 1)")
    app_id = _text(data, "androidApplicationId")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){2,}", app_id):
        raise ValueError("androidApplicationId must be a valid lower-case Android package")
    scheme = _text(data, "externalScheme")
    if not re.fullmatch(r"[a-z][a-z0-9+.-]*", scheme) or scheme in {
        "minis",
        "http",
        "https",
        "file",
        "content",
        "intent",
    }:
        raise ValueError("externalScheme must be a distinct lower-case URI scheme")
    theme = _text(data, "theme")
    if theme not in {"system", "light", "dark"}:
        raise ValueError("theme must be system, light or dark")
    updates = data.get("updates")
    if (
        not isinstance(updates, dict)
        or type(updates.get("enabled")) is not bool
        or "repositoryUrl" not in updates
    ):
        raise ValueError("updates requires enabled (boolean) and repositoryUrl")
    update_repo = updates["repositoryUrl"]
    if update_repo is not None:
        if not isinstance(update_repo, str):
            raise ValueError("updates.repositoryUrl must be a URL or null")
        update_repo = _https(update_repo, "updates.repositoryUrl", repository=True)
    if updates["enabled"] and update_repo is None:
        raise ValueError("enabled updates require a repositoryUrl")
    colors = data.get("themeColors")
    frozen_colors = {}
    for mode in ("light", "dark"):
        palette = colors.get(mode) if isinstance(colors, dict) else None
        required = {"background", "surface", "text", "primary", "onPrimary", "care", "outline"}
        if not isinstance(palette, dict) or not required.issubset(palette):
            raise ValueError(f"themeColors.{mode} requires all brand tokens")
        if any(
            not isinstance(v, str) or not re.fullmatch(r"#[0-9A-Fa-f]{6}", v)
            for v in palette.values()
        ):
            raise ValueError(f"themeColors.{mode} must contain RGB hex colors")
        frozen_colors[mode] = MappingProxyType({k: v.upper() for k, v in palette.items()})
    preserve = data.get("preserveNames")
    if not isinstance(preserve, list) or any(not isinstance(v, str) or not v for v in preserve):
        raise ValueError("preserveNames must be a list of names to retain in attribution/services")
    return BrandProfile(
        schemaVersion=1,
        displayName=_text(data, "displayName"),
        agentName=_text(data, "agentName"),
        androidApplicationId=app_id,
        externalScheme=scheme,
        defaultAvatar=_text(data, "defaultAvatar"),
        upstreamSourceUrl=_https(_text(data, "upstreamSourceUrl"), "upstreamSourceUrl"),
        theme=cast(Literal["system", "light", "dark"], theme),
        updates=UpdateConfig(updates["enabled"], update_repo),
        repositoryUrl=_https(_text(data, "repositoryUrl"), "repositoryUrl", repository=True),
        preserveNames=tuple(preserve),
        themeColors=MappingProxyType(frozen_colors),
    )
