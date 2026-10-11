"""Vector-derived Dot assets keep their platform contracts and deterministic output."""

from __future__ import annotations

import argparse
import importlib.util
import io
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_dot_states_keep_round_transparent_silhouette():
    dot = load("gen-nanodot-assets")
    masks = []
    for state in dot.STATES:
        image = dot.render(dot.svg(state), 512)
        assert image.mode == "RGBA"
        assert image.getpixel((0, 0))[3] == 0
        assert image.getpixel((256, 256))[3] == 255
        masks.append(image.getchannel("A").tobytes())
    assert len(set(masks)) == 1
    assert len({dot.svg(state) for state in dot.STATES}) == 5


def test_svg_renderer_and_webp_are_deterministic(tmp_path):
    dot = load("gen-nanodot-assets")
    target = tmp_path / "idle.webp"
    image = dot.render(dot.svg(), 512)
    dot.save(image, target)
    before = target.read_bytes()
    timestamp = target.stat().st_mtime_ns
    dot.save(dot.render(dot.svg(), 512), target)
    assert target.read_bytes() == before
    assert target.stat().st_mtime_ns == timestamp
    decoded = Image.open(io.BytesIO(before))
    assert decoded.size == (512, 512)
    assert decoded.mode == "RGBA"


def test_brand_config_palette_is_shared():
    dot = load("gen-nanodot-assets")
    primary = dot.profile(
        argparse.Namespace(brand_config=ROOT / "config/brand.nanodot.json", primary=dot.PRIMARY)
    )
    colors = json.loads((ROOT / "config/brand.nanodot.json").read_text())["themeColors"]
    assert primary == colors["light"]["primary"]
    assert dot.DARK_PRIMARY == colors["dark"]["primary"]
    assert dot.CARE == colors["light"]["care"]


def test_android_monochrome_vectors_cut_out_eyes():
    import xml.etree.ElementTree as ET

    android = load("gen-android-icons")
    dot = android.assets()
    vector = ET.fromstring(android.vector(dot.svg(), 108, 0.72, True))
    path = list(list(vector)[0])[0]
    prefix = "{http://schemas.android.com/apk/res/android}"
    assert path.attrib[prefix + "fillType"] == "evenOdd"
    assert path.attrib[prefix + "pathData"].count("M") == 3


def test_ios_and_desktop_native_icon_formats():
    for path in (ROOT / "android/src/ios/Assets.xcassets/AppIcon.appiconset").glob("*.png"):
        image = Image.open(path)
        if "1024" in path.name:
            assert image.size == (1024, 1024)
        assert image.mode == "RGB"
    assert Image.open(ROOT / "web/public/avatars/dragon-idle.webp").size == (1024, 1024)
    assert Image.open(ROOT / "harness/desktop/resources/app-icon.png").size == (1024, 1024)
    assert Image.open(ROOT / "harness/desktop/resources/logo.png").size == (256, 256)
    assert Image.open(ROOT / "harness/desktop/resources/app-icon.ico").format == "ICO"
    assert Image.open(ROOT / "harness/desktop/resources/app-icon.icns").format == "ICNS"
