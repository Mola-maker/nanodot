#!/usr/bin/env python3
"""Generate opaque Nanodot iOS icons using the shared original Dot artwork.

All app-icon appearances and alternate skins are regenerated, including upstream
unused icon files, so future asset-catalog references cannot restore old branding.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "android/src/ios"


def main() -> None:
    spec = importlib.util.spec_from_file_location(
        "dot_assets", ROOT / "scripts/gen-nanodot-assets.py"
    )
    assert spec is not None and spec.loader is not None
    dot = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(dot)
    primary = dot.profile(dot.arguments())
    # Retain upstream source-image sizes as well as the two active 1024px entries.
    sizes = {
        "Icon-full.png": 2048,
        "Icon-full-expanded.png": 2356,
        "Icon-full-expanded2.png": 2710,
        "Icon-full-dark.png": 1068,
        "Icon-full-dark-expanded.png": 1228,
        "Icon-full-dark-expanded2.png": 1412,
    }
    folders = (IOS / "Assets.xcassets/AppIcon.appiconset", IOS / "Resources/AlternateIcons")
    for folder in folders:
        for path in sorted(folder.glob("*.png")):
            dark = "dark" in path.name.lower()
            color = dot.DARK_PRIMARY if dark else primary
            dot.save(
                dot.render(
                    dot.svg(primary=color),
                    sizes.get(path.name, 1024),
                    dot.TILE_DARK if dark else dot.TILE_LIGHT,
                ),
                path,
                opaque=True,
            )
    print("Nanodot iOS app-icon appearances and alternate icons are current.")


if __name__ == "__main__":
    main()
