"""Brand configuration is validated before any generator writes files."""

import json
import tempfile
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path

from scripts import brand_profile

ROOT = Path(__file__).resolve().parents[1]


class BrandProfileTest(unittest.TestCase):
    def load(self, **overrides):
        data = json.loads((ROOT / "config/brand.nanodot.json").read_text())
        data.update(overrides)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "brand.json"
            path.write_text(json.dumps(data))
            return brand_profile.load_brand(path)

    def test_valid_immutable_contract(self):
        brand = self.load()
        self.assertEqual(
            ("Nanodot", "Dot", "io.github.nanodot.app", "nanodot"),
            (brand.displayName, brand.agentName, brand.androidApplicationId, brand.externalScheme),
        )
        self.assertEqual(("dot-v1", "system"), (brand.defaultAvatar, brand.theme))
        self.assertFalse(brand.updates.enabled)
        self.assertIsNone(brand.updates.repositoryUrl)
        with self.assertRaises(FrozenInstanceError):
            brand.displayName = "changed"
        with self.assertRaises(TypeError):
            brand.themeColors["light"]["primary"] = "#000000"

    def test_invalid_values_fail_before_generation(self):
        for values in (
            {"schemaVersion": 2},
            {"schemaVersion": True},
            {"androidApplicationId": "io.invalid-app"},
            {"androidApplicationId": "io.9invalid.app"},
            {"externalScheme": "nanodot://"},
            {"theme": "automatic"},
            {"updates": {"enabled": "false", "repositoryUrl": None}},
            {"updates": {"enabled": True, "repositoryUrl": None}},
            {"upstreamSourceUrl": "http://example.com"},
            {"defaultAvatar": ""},
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                self.load(**values)

    def test_missing_required_field(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "brand.json"
            path.write_text('{"schemaVersion": 1}')
            with self.assertRaises(ValueError):
                brand_profile.load_brand(path)

    def test_enabled_updates_require_a_github_repository(self):
        brand = self.load(
            updates={"enabled": True, "repositoryUrl": "https://github.com/Mola-maker/nanodot"}
        )
        self.assertTrue(brand.updates.enabled)
        with self.assertRaises(ValueError):
            self.load(updates={"enabled": True, "repositoryUrl": "https://nanodot.invalid"})
