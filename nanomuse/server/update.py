"""A cached release check for the explicitly enabled Nanodot update repository.

The generated brand contract is packaged in wheels and bundled runtimes. Until
``updates.enabled`` and ``updates.repositoryUrl`` are configured in the brand profile,
automatic and manual requests are inert. No upstream mirror or installer is offered.
``server.update_check``, ``NANOMUSE_NO_UPDATE_CHECK`` and hosted sessions can also
turn checking off. Only release metadata is requested; nothing is installed.
"""

from __future__ import annotations

import os
import re
import time
from datetime import UTC, datetime
from typing import Any

import httpx

from nanomuse import __version__, brand
from nanomuse.logger import logger

CACHE_S = 24 * 3600
TIMEOUT_S = 6.0


def parse_version(text: str) -> tuple[int, ...]:
    """``v0.1.21`` → ``(0, 1, 21)``; anything after the numbers (``-rc1``) is ignored,
    a string without numbers is ``()`` and therefore older than everything."""
    m = re.match(r"v?(\d+(?:\.\d+)*)", text.strip())
    return tuple(int(p) for p in m.group(1).split(".")) if m else ()


def newer_than(candidate: str, current: str) -> bool:
    a, b = parse_version(candidate), parse_version(current)
    return bool(a) and a > b


def repository_url() -> str | None:
    """Fail closed if a generated or locally modified channel is incomplete."""
    url = brand.UPDATE_REPOSITORY_URL
    if not brand.UPDATES_ENABLED or not isinstance(url, str):
        return None
    if not re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?", url):
        return None
    return url.rstrip("/")


def enabled(setting: bool) -> bool:
    if not setting or not repository_url():
        return False
    if os.environ.get("NANOMUSE_NO_UPDATE_CHECK", "").strip().lower() in (
        "1",
        "true",
        "yes",
        "on",
    ):
        return False
    return not os.environ.get("NANOMUSE_CLOUD_KEY")


class UpdateCheck:
    """The cached answer; :meth:`view` is what ``GET /api/update`` returns."""

    def __init__(self, setting: bool = True, *, current: str = __version__) -> None:
        self.setting = setting
        self.current = current
        self.latest: str | None = None
        self.repository = repository_url()
        self.url = f"{self.repository}/releases/latest" if self.repository else ""
        self.download_url = f"{self.repository}/releases" if self.repository else ""
        # monotonic, for the cache; the wall clock, for "checked at" on the About page
        self.checked_at: float | None = None
        self.checked_wall: str | None = None
        self.source: str | None = None
        self.error: str | None = None

    async def view(self, force: bool = False) -> dict[str, Any]:
        if not enabled(self.setting):
            return {
                "current": self.current,
                "enabled": False,
                "latest": None,
                "newer": False,
                "url": "",
                "download_url": "",
                "checked_at": None,
                "source": None,
                "error": None,
            }
        if force or self.checked_at is None or time.monotonic() - self.checked_at > CACHE_S:
            await self._check()
        return {
            "current": self.current,
            "enabled": True,
            "latest": self.latest,
            "newer": bool(self.latest and newer_than(self.latest, self.current)),
            "url": self.url,
            "download_url": self.download_url,
            "checked_at": self.checked_wall,
            "source": self.source,
            "error": self.error,
        }

    async def _check(self) -> None:
        if not enabled(self.setting) or not self.repository:
            return
        self.checked_at = time.monotonic()
        self.checked_wall = datetime.now(UTC).isoformat(timespec="seconds")
        headers = {
            "User-Agent": f"{brand.DISPLAY_NAME}/{self.current}",
            "Accept": "application/vnd.github+json",
        }
        repository = self.repository.removeprefix("https://github.com/")
        api_url = f"https://api.github.com/repos/{repository}/releases/latest"
        try:
            async with httpx.AsyncClient(follow_redirects=True) as client:
                response = await client.get(api_url, headers=headers, timeout=TIMEOUT_S)
                response.raise_for_status()
                data = response.json()
                tag = str(data.get("tag_name") or "")
                if not re.fullmatch(r"v?\d+(?:\.\d+)*(?:-[A-Za-z0-9.-]+)?", tag):
                    raise ValueError(f"unexpected tag {tag!r}")
                # Derive the offered link from the configured repository, never response URLs.
                self._found(tag, f"{self.repository}/releases/tag/{tag}", "github")
        except Exception as exc:  # noqa: BLE001 — checking never causes an app failure
            self.error = exc.__class__.__name__
            logger.debug("update check (github) failed: {}", exc)

    def _found(self, tag: str, url: str, source: str) -> None:
        self.latest = tag.lstrip("v")
        self.url = url
        self.source = source
        self.error = None
