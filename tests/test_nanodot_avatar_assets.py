"""Packaged crystal defaults regenerate without touching personal or other-platform files."""

from __future__ import annotations

import argparse
import importlib.util
import tempfile
import unittest
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = Path("android/src/android/app/src/main/res")


class CrystalAvatarAssetsTest(unittest.TestCase):
    def setUp(self) -> None:
        spec = importlib.util.spec_from_file_location(
            "dot_avatar_assets", ROOT / "scripts/gen-nanodot-assets.py"
        )
        self.dot = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.dot)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dot.ROOT = self.root
        self.dot.BRAND = self.root / "assets/brand"
        self.dot.RES = self.root / RES
        self.dot.IOS = self.root / "android/src/ios"
        self.primary = self.dot.profile(
            argparse.Namespace(
                brand_config=ROOT / "config/brand.nanodot.json", primary=self.dot.PRIMARY
            )
        )

    def test_default_is_the_existing_crystal_frame_and_shared_by_platforms(self) -> None:
        self.dot.avatars(self.primary)
        # The same inset as the Android orb: first 313x313 cell, 31px inset.
        with Image.open(ROOT / RES / "drawable-nodpi/nm_crystal_orb_atlas.png") as atlas:
            expected = atlas.convert("RGBA").crop((31, 31, 282, 282))
            expected = expected.resize((1024, 1024), Image.Resampling.LANCZOS)
        android = self.dot.RES / "drawable-nodpi/nm_avatar_idle.webp"
        with Image.open(android) as generated:
            self.assertEqual(expected.tobytes(), generated.convert("RGBA").tobytes())
        for folder in (
            "web/public/avatars",
            "harness/dsh-nanomuse/assets",
            "android/src/ios/NanoMuse/Resources",
        ):
            self.assertEqual(
                android.read_bytes(), (self.root / folder / "dragon-idle.webp").read_bytes()
            )

    def test_android_target_is_idempotent_and_preserves_other_and_custom_assets(self) -> None:
        sentinels = {
            "files/minis-global/nanomuse/avatar/base.png": b"user-selected-face",
            "files/minis-global/nanomuse/avatar/avatar.json": b'{"model":"user-choice"}',
            "files/minis-global/memory/SOUL.md": b'name: "nanoMuse, my own name"',
            "harness/dsh-nanomuse/assets/dragon-idle.webp": b"other-platform",
            "web/public/avatars/dragon-idle.webp": b"other-platform",
            "android/src/ios/NanoMuse/Resources/dragon-idle.webp": b"other-platform",
        }
        for relative, data in sentinels.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.dot.avatars(self.primary, android_only=True)
        first = {
            p: (p.read_bytes(), p.stat().st_mtime_ns)
            for p in self.dot.RES.rglob("*")
            if p.is_file()
        }
        self.assertEqual(5, len(first))
        self.dot.avatars(self.primary, android_only=True)
        for path, before in first.items():
            self.assertEqual(before, (path.read_bytes(), path.stat().st_mtime_ns))
            with Image.open(path) as image:
                self.assertEqual("RGBA", image.mode)
                self.assertEqual((1024, 1024), image.size)
                self.assertLess(image.getpixel((0, 0))[3], 5)
        for relative, data in sentinels.items():
            self.assertEqual(data, (self.root / relative).read_bytes())
        self.assertFalse(self.dot.BRAND.exists())

    def test_changed_atlas_is_rejected_before_writing_resources(self) -> None:
        changed = self.root / "wrong-atlas.png"
        changed.write_bytes(b"not the reviewed crystal atlas")
        self.dot.CRYSTAL_ATLAS = changed
        with self.assertRaisesRegex(ValueError, "atlas.*hash"):
            self.dot.avatars(self.primary, android_only=True)
        self.assertFalse(self.dot.RES.exists())


if __name__ == "__main__":
    unittest.main()
