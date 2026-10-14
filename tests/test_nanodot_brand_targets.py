"""An alternate profile reaches every platform without replacing unrelated values."""

import json
import re
import shutil
from pathlib import Path

from scripts.brand_profile import load_brand
from scripts.rebrand import apply_brand

ROOT = Path(__file__).resolve().parents[1]
THEME = Path("android/src/android/app/src/main/java/com/openminis/app/ui/theme/Theme.kt")
INSTALLER = Path("android/src/android/app/src/main/java/com/openminis/app/data/UpdateChecker.kt")
ANDROID_UPDATE = Path(
    "android/src/android/app/src/main/java/io/github/nanomuse/community/UpdateCheck.kt"
)
IOS_UPDATE = Path("android/src/ios/NanoMuse/NanoMuseUpdateCheck.swift")
IOS_TONES = Path("android/src/ios/NanoMuse/NanoMuseFaces.swift")
IOS_ACCENT = Path("android/src/ios/Assets.xcassets/AccentColor.colorset/Contents.json")
DESK = Path("harness/dsh-nanomuse/src/desk.ts")
DESKTOP_STYLE = Path("harness/dsh-nanomuse/src/client/styles.ts")


def fixture(tmp_path):
    root = tmp_path / "tree"
    for relative in (
        THEME,
        INSTALLER,
        ANDROID_UPDATE,
        IOS_UPDATE,
        IOS_TONES,
        IOS_ACCENT,
        DESK,
        DESKTOP_STYLE,
        Path("web/src/index.css"),
        Path("web/src/theme.ts"),
        Path("web/index.html"),
        Path("web/public/manifest.webmanifest"),
        Path("harness/desktop/src/main.ts"),
    ):
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, destination)
    preserved = {
        "profile.json": '{"name":"My companion","avatar":"custom-face","color":"#136F63","theme":"dark"}',
        "SOUL.md": "My own Dot instructions and #7DD6C5 accent",
        "config/config.toml": '[agent]\nname = "Lumi"\n[cloud]\nbase_url = "https://cloud.nanomuse.cn"',
    }
    for relative, value in preserved.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")
    with (root / "web/src/index.css").open("a", encoding="utf-8") as source:
        source.write("\n.custom-chip { color: #136F63; }\n")
    return root, preserved


def alternate_brand(tmp_path, *, enabled=True):
    data = json.loads((ROOT / "config/brand.nanodot.json").read_text(encoding="utf-8"))
    data["updates"] = {
        "enabled": enabled,
        "repositoryUrl": "https://github.com/Mola-maker/nanodot-releases",
    }
    for mode, offset in (("light", 0), ("dark", 16)):
        for i, token in enumerate(data["themeColors"][mode]):
            data["themeColors"][mode][token] = f"#{offset + i + 1:02X}2233"
    path = tmp_path / "brand.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return load_brand(path)


def css_block(text, selector):
    return re.search(re.escape(selector) + r" \{([^}]+)\}", text).group(1)


def desktop_colors(root):
    source = (root / "harness/dsh-nanomuse/src/client/dot-tokens.ts").read_text(encoding="utf-8")
    return json.loads(re.search(r"export const dotTokens = ([\s\S]+) as const;", source)[1])[
        "colors"
    ]


def test_explicit_update_channel_reaches_every_platform_and_is_idempotent(tmp_path):
    root, preserved = fixture(tmp_path)
    brand = alternate_brand(tmp_path)
    assert apply_brand(root, brand)
    api = "https://api.github.com/repos/Mola-maker/nanodot-releases/releases/latest"
    repo = brand.updates.repositoryUrl
    assert 'private const val REPO = "nanodot-releases"' in (root / INSTALLER).read_text(
        encoding="utf-8"
    )
    assert f"{repo}/releases" in (root / ANDROID_UPDATE).read_text(encoding="utf-8")
    assert api in (root / ANDROID_UPDATE).read_text(encoding="utf-8")
    assert api in (root / IOS_UPDATE).read_text(encoding="utf-8")
    assert "private static let updatesEnabled = true" in (root / IOS_UPDATE).read_text(
        encoding="utf-8"
    )
    assert f"{repo}/releases/latest" in (root / IOS_UPDATE).read_text(encoding="utf-8")
    assert api in (root / DESK).read_text(encoding="utf-8")
    assert "export const UPDATE_CHECKS_ENABLED = true" in (root / DESK).read_text(encoding="utf-8")
    assert f"{repo}/releases/latest" in (root / "harness/desktop/src/main.ts").read_text(
        encoding="utf-8"
    )
    assert brand.repositoryUrl in (root / "nanomuse/brand.py").read_text(encoding="utf-8")
    assert repo in (root / "nanomuse/brand.py").read_text(encoding="utf-8")
    for relative, value in preserved.items():
        assert (root / relative).read_text(encoding="utf-8") == value
    assert apply_brand(root, brand) == []


def test_disabling_and_restoring_the_profile_updates_existing_generated_values(tmp_path):
    root, _ = fixture(tmp_path)
    active = alternate_brand(tmp_path)
    apply_brand(root, active)
    disabled = alternate_brand(tmp_path, enabled=False)
    apply_brand(root, disabled)
    assert "export const UPDATE_CHECKS_ENABLED = false" in (root / DESK).read_text(encoding="utf-8")
    assert "private static let updatesEnabled = false" in (root / IOS_UPDATE).read_text(
        encoding="utf-8"
    )
    assert "UPDATES_ENABLED = false" in (
        root / "android/src/android/app/src/main/java/io/github/nanomuse/dot/DotBrand.kt"
    ).read_text(encoding="utf-8")
    assert "UPDATES_ENABLED = False" in (root / "nanomuse/brand.py").read_text(encoding="utf-8")
    original = load_brand(ROOT / "config/brand.nanodot.json")
    apply_brand(root, original)
    assert "nanodot-releases" not in (root / DESK).read_text(encoding="utf-8")
    assert "nanodot-releases" not in (root / IOS_UPDATE).read_text(encoding="utf-8")
    light = original.themeColors["light"]
    dark = original.themeColors["dark"]
    assert f"static let action = tone(0x{light['primary'][1:]}, 0x{dark['primary'][1:]})" in (
        root / IOS_TONES
    ).read_text(encoding="utf-8")
    assert f"--om-accent: {light['primary']};" in (root / "web/src/index.css").read_text(
        encoding="utf-8"
    )
    assert desktop_colors(root)["dark"]["primary"] == dark["primary"]
    assert apply_brand(root, original) == []


def test_alternate_palettes_reach_semantic_tokens_and_preserve_unrelated_colors(tmp_path):
    root, preserved = fixture(tmp_path)
    brand = alternate_brand(tmp_path)
    apply_brand(root, brand)
    web = (root / "web/src/index.css").read_text(encoding="utf-8")
    desktop = (root / DESKTOP_STYLE).read_text(encoding="utf-8")
    colors = desktop_colors(root)
    swift = (root / IOS_TONES).read_text(encoding="utf-8")
    for mode, selector in (
        ("light", ":root"),
        ("dark", 'html[data-theme="dark"]'),
    ):
        palette = brand.themeColors[mode]
        block = css_block(web, selector)
        for prop, token in {
            "bg": "background",
            "surface": "surface",
            "fg": "text",
            "muted": "text",
            "accent": "primary",
            "accent-fg": "onPrimary",
            "care": "care",
        }.items():
            assert f"--om-{prop}: {palette[token]};" in block
        assert colors[mode]["background"] == palette["background"]
        assert colors[mode]["surface"] == palette["surface"]
        assert palette["background"] in (root / "web/src/theme.ts").read_text(encoding="utf-8")
        assert palette["background"] in (root / "web/index.html").read_text(encoding="utf-8")
        assert palette["background"] in (root / "harness/desktop/src/main.ts").read_text(
            encoding="utf-8"
        )
    for field, token in {
        "canvas": "background",
        "surface": "surface",
        "action": "primary",
        "onAction": "onPrimary",
        "care": "care",
        "hairline": "outline",
    }.items():
        values = [brand.themeColors[mode][token][1:] for mode in ("light", "dark")]
        assert f"static let {field} = tone(0x{values[0]}, 0x{values[1]})" in swift
    assert colors["light"]["primary"] == brand.themeColors["light"]["primary"]
    assert colors["dark"]["onPrimary"] == brand.themeColors["dark"]["onPrimary"]
    assert desktop == (ROOT / DESKTOP_STYLE).read_text(encoding="utf-8")
    theme = (root / THEME).read_text(encoding="utf-8")
    assert "private val DarkColorScheme = io.github.nanomuse.dot.DotColors.darkScheme" in theme
    colors = (
        root / "android/src/android/app/src/main/java/io/github/nanomuse/dot/DotColors.kt"
    ).read_text(encoding="utf-8")
    assert f"tertiary = Color(0xFF{brand.themeColors['dark']['care'][1:]})," in colors
    assert f"onTertiary = Color(0xFF{brand.themeColors['dark']['onPrimary'][1:]})," in colors
    assert ".custom-chip { color: #136F63; }" in web
    for relative, value in preserved.items():
        assert (root / relative).read_text(encoding="utf-8") == value
    assert apply_brand(root, brand) == []


def test_ios_system_accent_uses_each_configured_primary_and_preserves_asset_metadata(tmp_path):
    root, _ = fixture(tmp_path)
    brand = alternate_brand(tmp_path)
    before = json.loads((root / IOS_ACCENT).read_text(encoding="utf-8"))
    apply_brand(root, brand)
    asset = json.loads((root / IOS_ACCENT).read_text(encoding="utf-8"))
    assert asset["info"] == before["info"]
    for entry in asset["colors"]:
        mode = "dark" if entry.get("appearances") else "light"
        color = brand.themeColors[mode]["primary"].lstrip("#")
        components = entry["color"]["components"]
        for key, offset in (("red", 0), ("green", 2), ("blue", 4)):
            assert abs(float(components[key]) - int(color[offset : offset + 2], 16) / 255) < 1e-6
        assert components["alpha"] == "1.000"
        assert entry["idiom"] == "universal"
    assert apply_brand(root, brand) == []
