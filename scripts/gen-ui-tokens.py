#!/usr/bin/env python3
"""Generate the native Nanodot UI tokens from the UI and brand authorities."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Support direct CLI and importlib loading of this deliberately hyphenated entry point.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from ui_tokens import contrast, generate_ui_tokens, material_roles  # noqa: E402,F401


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--brand", type=Path, default=root / "config/brand.nanodot.json")
    parser.add_argument("--ui", type=Path, default=root / "config/ui.nanodot.json")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        current = generate_ui_tokens(args.brand, args.ui, args.check)
    except (ValueError, OSError) as error:
        parser.exit(2, f"UI tokens: {error}\n")
    print("UI tokens current" if current else "UI tokens stale; run scripts/gen-ui-tokens.py")
    return 0 if current else 1


if __name__ == "__main__":
    raise SystemExit(main())
