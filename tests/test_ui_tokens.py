"""Token generation validates first, stays reproducible and shares brand ownership."""

import importlib.util
import json
import shutil
from pathlib import Path

import pytest

from scripts.brand_profile import load_brand
from scripts.rebrand import apply_brand

ROOT = Path(__file__).resolve().parents[1]
ANDROID = Path("android/src/android/app/src/main/java")
OUTPUTS = (
    ANDROID / "io/github/nanomuse/dot/DotColors.kt",
    ANDROID / "io/github/nanomuse/dot/ui/DotTokens.kt",
    Path("android/src/ios/NanoMuse/NanodotTokens.swift"),
    Path("web/src/dot-tokens.css"),
    Path("harness/dsh-nanomuse/src/client/dot-tokens.ts"),
    Path("site/dot-tokens.css"),
)


def generator():
    path = ROOT / "scripts/gen-ui-tokens.py"
    assert path.exists(), "the UI token generator must exist"
    spec = importlib.util.spec_from_file_location("ui_tokens", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def tree(tmp_path):
    root = tmp_path / "tree"
    (root / "config").mkdir(parents=True)
    for name in ("brand.nanodot.json", "ui.nanodot.json"):
        source = ROOT / "config" / name
        assert source.exists(), f"missing token source: {name}"
        shutil.copyfile(source, root / "config" / name)
    return root


def run(module, root, check=False):
    return module.generate_ui_tokens(
        root / "config/brand.nanodot.json", root / "config/ui.nanodot.json", check=check
    )


def snapshot(root):
    return {p: (root / p).read_bytes() for p in OUTPUTS}


def test_generator_exists():
    assert callable(generator().generate_ui_tokens)


def test_source_values_outputs_and_idempotence(tree):
    module = generator()
    assert not run(module, tree, check=True)
    assert run(module, tree)
    first = snapshot(tree)
    stamps = {p: (tree / p).stat().st_mtime_ns for p in OUTPUTS}
    assert run(module, tree) and run(module, tree, check=True)
    assert snapshot(tree) == first
    assert {p: (tree / p).stat().st_mtime_ns for p in OUTPUTS} == stamps
    ui = json.loads((tree / "config/ui.nanodot.json").read_text())
    assert ui["space"] == [4, 8, 12, 16, 24, 32, 48]
    assert ui["type"] == {"body": 16, "care": 18}
    assert ui["touchTarget"] == 48
    assert ui["motion"] == {"page": 180, "press": 100, "focus": 200}
    css = first[Path("web/src/dot-tokens.css")].decode()
    assert css == first[Path("site/dot-tokens.css")].decode()
    brand = load_brand(tree / "config/brand.nanodot.json")
    for palette in brand.themeColors.values():
        for key, value in palette.items():
            css_key = "on-primary" if key == "onPrimary" else key
            assert f"--dot-{css_key}: {value};" in css
            assert f'"{key}": "{value}"' in first[OUTPUTS[4]].decode()
            assert f"0x{value[1:]}" in first[OUTPUTS[2]].decode()
    ts = first[OUTPUTS[4]].decode()
    # The TypeScript export is a literal JSON object plus a type assertion.
    ts_values = json.loads(ts.split("export const dotTokens = ", 1)[1].removesuffix(" as const;\n"))
    assert ts_values["colors"] == {
        mode: dict(palette) for mode, palette in brand.themeColors.items()
    }
    for group in ("space", "type", "motion", "touchTarget"):
        assert ts_values[group] == ui[group]
    swift = first[OUTPUTS[2]].decode()
    assert "enum Typography" in swift
    for key, value in ui["type"].items():
        assert f"static let {key}: CGFloat = {value}" in swift
        assert f"--dot-type-{key}: {value / 16:g}rem;" in css
    for key, value in ui["motion"].items():
        assert f"static let {key}: Double = {value / 1000:g}" in swift
        assert f"--dot-motion-{key}: {value}ms;" in css
    assert f"static let touchTarget: CGFloat = {ui['touchTarget']}" in swift
    assert f"--dot-touch-target: {ui['touchTarget']}px;" in css
    kt = first[OUTPUTS[1]].decode()
    assert "val Body = 16.sp" in kt and "val Care = 18.sp" in kt
    assert "val TouchTarget = 48.dp" in kt
    assert "const val Page = 180" in kt and "const val Press = 100" in kt
    assert "const val Focus = 200" in kt
    for value in ui["space"]:
        assert f"val S{value} = {value}.dp" in kt
        assert f"--dot-space-{value}: {value}px;" in css
        assert f"static let s{value}: CGFloat = {value}" in swift
    (tree / OUTPUTS[3]).write_text("stale")
    assert not run(module, tree, check=True)
    assert (tree / OUTPUTS[3]).read_text() == "stale"


@pytest.mark.parametrize(
    "mutation", ["color", "contrast", "unknown", "negative", "bool", "missing", "motion"]
)
def test_invalid_input_does_not_modify_any_output(tree, mutation):
    module = generator()
    run(module, tree)
    before = snapshot(tree)
    name = (
        "brand.nanodot.json" if mutation in {"color", "contrast", "unknown"} else "ui.nanodot.json"
    )
    path = tree / "config" / name
    data = json.loads(path.read_text())
    if mutation == "color":
        data["themeColors"]["dark"]["primary"] = "#oops"
    elif mutation == "contrast":
        data["themeColors"]["light"]["onPrimary"] = data["themeColors"]["light"]["primary"]
    elif mutation == "unknown":
        data["themeColors"]["light"]["invalid-token"] = "#FFFFFF"
    elif mutation == "negative":
        data["space"][0] = -4
    elif mutation == "bool":
        data["type"]["body"] = True
    elif mutation == "missing":
        del data["touchTarget"]
    else:
        data["motion"]["focus"] = -1
    path.write_text(json.dumps(data))
    with pytest.raises(ValueError):
        run(module, tree)
    assert snapshot(tree) == before


def test_material_roles_and_contrast(tree):
    module = generator()
    run(module, tree)
    brand = load_brand(tree / "config/brand.nanodot.json")
    for mode, palette in brand.themeColors.items():
        assert module.contrast(palette["text"], palette["surface"]) >= 4.5
        assert module.contrast(palette["onPrimary"], palette["primary"]) >= 4.5
        roles = module.material_roles(brand, mode)
        assert roles["onPrimary"] == palette["onPrimary"]
        assert roles["onSecondary"] == palette["onPrimary"]
        assert roles["onTertiary"] == palette["onPrimary"]
        for role in (
            "onBackground",
            "onSurface",
            "onSurfaceVariant",
            "onPrimaryContainer",
            "onSecondaryContainer",
            "onTertiaryContainer",
            "onError",
            "onErrorContainer",
        ):
            background = role[2].lower() + role[3:]
            assert module.contrast(roles[role], roles[background]) >= 4.5
        assert (
            roles["inverseOnSurface"]
            == brand.themeColors["dark" if mode == "light" else "light"]["text"]
        )
    colors = (tree / OUTPUTS[0]).read_text()
    for role in module.material_roles(brand, "light"):
        assert f"{role} = " in colors


def test_rebrand_cannot_overwrite_token_material_mapping(tree):
    module = generator()
    run(module, tree)
    theme = tree / ANDROID / "com/openminis/app/ui/theme/Theme.kt"
    theme.parent.mkdir(parents=True)
    theme.write_text(
        "package com.openminis.app.ui.theme\n// nanoMuse: shared DotColors schemes\n"
        "private val LightColorScheme = io.github.nanomuse.dot.DotColors.lightScheme\n"
        "private val DarkColorScheme = io.github.nanomuse.dot.DotColors.darkScheme\n"
    )
    before = snapshot(tree)
    apply_brand(tree, load_brand(tree / "config/brand.nanodot.json"))
    assert snapshot(tree) == before
    assert apply_brand(tree, load_brand(tree / "config/brand.nanodot.json")) == []
