"""An optional project-key connection, independent of active slots and Cloud accounts."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import httpx

from nanomuse.vault.vault import CredentialVault

PROVIDER_ID = "nanodot-workers"
ORIGIN = "https://molamaker.si"
BASE_URL = ORIGIN + "/v1"


def is_fresh(config_loaded: bool, has_account: bool, has_provider: bool) -> bool:
    return config_loaded and not has_account and not has_provider


def gateway_base(value: str) -> str:
    url = urlsplit(value)
    if (
        url.scheme != "https"
        or url.hostname != "molamaker.si"
        or url.port not in (None, 443)
        or url.username is not None
        or url.password is not None
        or url.path not in ("", "/", "/v1", "/v1/")
        or url.query
        or url.fragment
    ):
        raise ValueError("The project key belongs to https://molamaker.si/v1")
    return BASE_URL


def may_inherit_project_key(provider: str, key_ref: str, target: str) -> bool:
    """Only the Nanodot profile/reference changes existing implicit-key inheritance."""
    if provider != PROVIDER_ID and not key_ref.startswith("{{vault:NANODOT_GATEWAY_"):
        return True
    try:
        url = urlsplit(target)
        return (
            url.scheme == "https"
            and url.hostname == "molamaker.si"
            and url.port in (None, 443)
            and url.username is None
            and url.password is None
        )
    except ValueError:
        return False


class GatewayProfiles:
    """Store only UUID, origin and live modalities beside the existing encrypted vault."""

    def __init__(self, path: Path, vault: CredentialVault):
        self.path = path
        self.vault = vault
        self._lock = asyncio.Lock()

    def load(self) -> dict[str, Any]:
        return json.loads(self.path.read_text()) if self.path.exists() else {}

    async def connect(
        self, credential: str, http: httpx.AsyncClient | None = None
    ) -> dict[str, Any]:
        key = credential.strip()
        if not key or any(c.isspace() for c in key):
            raise ValueError("A dedicated project key is required")
        async with self._lock:
            previous = self.load()
            if previous:
                gateway_base(previous["base_url"])
            if http is None:
                async with httpx.AsyncClient(trust_env=False, timeout=15) as client:
                    models = await self._models(client, key)
            else:
                models = await self._models(http, key)
            instance_id = previous.get("instance_id") or str(uuid4())
            key_ref = "NANODOT_GATEWAY_" + instance_id.replace("-", "_").upper()
            profile = {
                "provider": PROVIDER_ID,
                "instance_id": instance_id,
                "base_url": BASE_URL,
                "key_ref": key_ref,
                "models": models,
                "tts_zh_verified": False,
            }
            self.vault.set(key_ref, key)
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n")
            tmp.replace(self.path)
            return profile

    @staticmethod
    async def _models(http: httpx.AsyncClient, key: str) -> list[dict[str, Any]]:
        response = await http.get(BASE_URL + "/models", headers={"Authorization": "Bearer " + key})
        if not response.is_success:
            raise ValueError("The gateway refused the model list; existing models were kept")
        try:
            rows = response.json()["data"]
            if not isinstance(rows, list):
                raise ValueError("Invalid model list")
            models = []
            for row in rows:
                if not isinstance(row, dict):
                    raise ValueError("Invalid model row")
                architecture = row.get("architecture") or {}
                if not isinstance(architecture, dict):
                    raise ValueError("Invalid model architecture")
                inputs = row.get("input_modalities", architecture.get("input_modalities"))
                outputs = row.get("output_modalities", architecture.get("output_modalities"))
                if (
                    isinstance(row.get("id"), str)
                    and isinstance(inputs, list)
                    and isinstance(outputs, list)
                    and inputs
                    and outputs
                    and all(isinstance(value, str) for value in inputs + outputs)
                ):
                    models.append(
                        {"id": row["id"], "input_modalities": inputs, "output_modalities": outputs}
                    )
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("The gateway returned an invalid model list") from exc
        if not models:
            raise ValueError("The gateway did not list any models; existing models were kept")
        return models
