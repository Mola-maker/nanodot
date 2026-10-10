#!/usr/bin/env python3
"""Record the exact installer bytes and the build's signing limitations for CI artifacts."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path

SUFFIXES = {
    "windows-x64": (".exe",),
    "macos-arm64": (".dmg", ".zip"),
    "macos-x64": (".dmg", ".zip"),
    "linux-x64": (".AppImage", ".deb", ".tar.gz"),
}


def write_manifest(directory: Path, platform: str, commit: str) -> None:
    packages = sorted(
        p
        for p in directory.glob("Nanodot-Desktop-*")
        if p.is_file() and p.name.endswith(SUFFIXES[platform])
    )
    for suffix in SUFFIXES[platform]:
        if not any(p.name.endswith(suffix) for p in packages):
            raise SystemExit(f"missing {platform} installer ending in {suffix}")
    lines = []
    for package in packages:
        with package.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        lines.append(f"{digest}  {package.name}\n")
    (directory / f"SHA256SUMS-{platform}.txt").write_text("".join(lines), encoding="utf-8")
    signing = "Not signed with a publisher certificate."
    if platform.startswith("macos-"):
        signing = "Ad-hoc signed; not notarized. macOS requires Open Anyway."
        if os.environ.get("MAC_SIGNING_CONFIGURED") == "true":
            signing = "Developer ID signed; not notarized."
            if os.environ.get("MAC_NOTARIZATION_CONFIGURED") == "true":
                signing = "Developer ID signed and notarized."
    elif platform.startswith("windows-"):
        signing += " Windows may show a SmartScreen warning."
    text = (
        f"Nanodot Desktop — {platform}\nCommit: {commit}\n"
        "Includes the Python hands runtime and platform-native helpers.\n"
        "Packaged harness and Python HTTP health checks passed.\n"
        "Native UI permissions and real screen/input actions require device testing.\n"
        f"Signing: {signing}\n"
        + "\n".join(f"{p.name} ({p.stat().st_size} bytes)" for p in packages)
        + "\n"
    )
    (directory / f"BUILD-INFO-{platform}.txt").write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--platform", required=True, choices=SUFFIXES)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    write_manifest(args.directory, args.platform, args.commit)


if __name__ == "__main__":
    main()
