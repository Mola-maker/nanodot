#!/usr/bin/env python3
"""Generate all Nanodot Android launcher and notification icons from original Dot SVG.

Run after upstream pulls; Pillow is required. No browser or optional raster step.
Existing upstream resource names stay stable for adaptive/legacy icon references.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/src/android/app/src/main/res"


def assets():  # noqa: ANN201
    spec = importlib.util.spec_from_file_location(
        "dot_assets", ROOT / "scripts/gen-nanodot-assets.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def vector(source: str, viewport: int, scale: float, monochrome: bool = False) -> str:
    import xml.etree.ElementTree as ET

    paths = []
    for path in ET.fromstring(source):
        if path.tag.rsplit("}", 1)[-1] != "path":
            continue
        attributes = path.attrib
        fill = attributes.get("fill", "none")
        if monochrome and fill != "none" and fill != "#136F63":
            fill = "#00000000"
        elif monochrome and fill != "none":
            fill = "#FFFFFFFF"
        elif fill == "none":
            fill = "#00000000"
        stroke = attributes.get("stroke")
        extra = (
            (
                f' android:strokeColor="{"#00000000" if monochrome else stroke}"'
                f' android:strokeWidth="{attributes.get("stroke-width", "1")}"'
                ' android:strokeLineCap="round"'
            )
            if stroke
            else ""
        )
        paths.append(
            f'        <path android:pathData="{attributes["d"]}" '
            f'android:fillColor="{fill}"{extra} />'
        )
    offset = (viewport - 100 * scale) / 2
    # For alpha masks, evenOdd removes the eye and mouth holes rather than painting
    # transparent shapes over a solid body (VectorDrawable has no clear blend mode).
    if monochrome:
        body = ET.fromstring(source)[0].attrib["d"]
        holes = " ".join(p.attrib["d"] for p in ET.fromstring(source)[1:3])
        paths = [
            f'        <path android:pathData="{body} {holes}" '
            'android:fillColor="#FFFFFFFF" android:fillType="evenOdd" />'
        ]
    return (
        f'<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- nanoMuse: original Nanodot Dot artwork; generated; GPL-3.0-or-later. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{viewport}dp" android:height="{viewport}dp"\n'
        f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n'
        f'    <group android:scaleX="{scale}" android:scaleY="{scale}" '
        f'android:translateX="{offset}" android:translateY="{offset}">\n'
        + "\n".join(paths)
        + "\n    </group>\n</vector>\n"
    )


def main() -> None:
    dot = assets()
    primary = dot.profile(dot.arguments())
    source = dot.svg(primary=primary)
    drawable = RES / "drawable"
    for name, color, mono in (
        ("ic_launcher_foreground_nm", primary, False),
        ("ic_launcher_foreground_nm_dark", dot.DARK_PRIMARY, False),
        ("ic_launcher_monochrome", dot.PRIMARY, True),
    ):
        dot.write(drawable / f"{name}.xml", vector(dot.svg(primary=color), 108, 0.72, mono))
    dot.write(drawable / "ic_stat_nanomuse.xml", vector(dot.svg(), 24, 0.25, True))
    skins = (
        ("", "@color/ic_launcher_background", "@drawable/ic_launcher_foreground_nm"),
        (
            "_classic_light",
            "@color/ic_launcher_bg_force_light",
            "@drawable/ic_launcher_foreground_nm",
        ),
        (
            "_classic_dark",
            "@color/ic_launcher_bg_force_dark",
            "@drawable/ic_launcher_foreground_nm_dark",
        ),
    )
    for folder in ("mipmap-anydpi-v26", "mipmap-night-anydpi-v26"):
        for suffix, bg, fg in skins:
            dot.write(
                RES / folder / f"ic_launcher{suffix}.xml",
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<!-- nanoMuse: generated Nanodot adaptive icon. -->\n"
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                f'    <background android:drawable="{bg}" />\n'
                f'    <foreground android:drawable="{fg}" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                "</adaptive-icon>\n",
            )
    for folder, color in (("values", dot.TILE_LIGHT), ("values-night", dot.TILE_DARK)):
        dot.write(
            RES / folder / "ic_launcher_background.xml",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- nanoMuse: generated Nanodot launcher background. -->\n"
            f'<resources><color name="ic_launcher_background">{color}</color></resources>\n',
        )
    dot.write(
        RES / "values/ic_launcher_alt_colors.xml",
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- nanoMuse: generated Nanodot launcher backgrounds. -->\n"
        f'<resources><color name="ic_launcher_bg_force_light">{dot.TILE_LIGHT}</color>'
        f'<color name="ic_launcher_bg_force_dark">{dot.TILE_DARK}</color></resources>\n',
    )
    for density, k in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        for night in (False, True):
            folder = RES / f"mipmap-{'night-' if night else ''}{density}"
            night_source = dot.svg(primary=dot.DARK_PRIMARY) if night else source
            for target in sorted(folder.glob("ic_launcher*.png")):
                foreground = "foreground" in target.name
                content = (
                    dot.svg(primary=dot.DARK_PRIMARY)
                    if "dark" in target.name
                    else source
                    if "light" in target.name
                    else night_source
                )
                dot.save(
                    dot.render(
                        content,
                        round((108 if foreground else 48) * k),
                        None if foreground else dot.TILE_DARK if night else dot.TILE_LIGHT,
                        scale=2 / 3 if foreground else 1,
                    ),
                    target,
                )
    print("Nanodot Android vector/adaptive/notification and legacy PNG icons are current.")


if __name__ == "__main__":
    main()
