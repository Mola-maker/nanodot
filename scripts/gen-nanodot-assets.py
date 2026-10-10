#!/usr/bin/env python3
"""Generate Dot vectors and platform assets deterministically with Pillow.

Artwork is original GPL-3.0-or-later artwork for Nanodot. SVGs are the source;
rasters use a small renderer for their absolute M/L/C/Z path vocabulary.
"""

from __future__ import annotations

import argparse
import io
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "assets/brand"
RES = ROOT / "android/src/android/app/src/main/res"
IOS = ROOT / "android/src/ios"
STATES = ("idle", "working", "waiting", "happy", "error")
PRIMARY = "#136F63"
DARK_PRIMARY = "#7DD6C5"
CARE = "#7057A3"
TILE_LIGHT = "#FFFFFF"
TILE_DARK = "#10191D"
DARK_FACE = "#10191D"


def write(path: Path, data: bytes | str) -> None:
    """Do not touch timestamps when the generated contents already match."""
    content = data.encode() if isinstance(data, str) else data
    if not path.exists() or path.read_bytes() != content:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)


def circle(cx: float, cy: float, r: float) -> str:
    k = r * 0.5522847498
    return (
        f"M{cx + r} {cy}C{cx + r} {cy + k} {cx + k} {cy + r} {cx} {cy + r}"
        f"C{cx - k} {cy + r} {cx - r} {cy + k} {cx - r} {cy}"
        f"C{cx - r} {cy - k} {cx - k} {cy - r} {cx} {cy - r}"
        f"C{cx + k} {cy - r} {cx + r} {cy - k} {cx + r} {cy}Z"
    )


def svg(state: str = "idle", primary: str = PRIMARY) -> str:
    eyes = [(39, 46), (61, 46)]
    mouth = "M42 61C46 66 54 66 58 61"
    if state == "working":
        eyes = [(42, 46), (64, 46)]
        mouth = "M45 62C48 64 54 64 57 62"
    elif state == "waiting":
        mouth = "M45 63C48 65 52 65 55 63"
    elif state == "happy":
        mouth = "M40 59C45 70 55 70 60 59"
    elif state == "error":
        mouth = "M43 66C47 62 53 62 57 66"
    face = DARK_FACE if primary == DARK_PRIMARY else "#F4FFFC"
    paths = [f'<path d="{circle(50, 50, 37)}" fill="{primary}"/>']
    for x, y in eyes:
        paths.append(f'<path d="{circle(x, y, 3)}" fill="{face}"/>')
    paths.append(
        f'<path d="{mouth}" fill="none" stroke="{face}" stroke-width="2.7" stroke-linecap="round"/>'
    )
    if state == "waiting":
        paths.append(f'<path d="{circle(70, 29, 2.7)}" fill="{CARE}"/>')
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">\n'
        "<!-- Original Nanodot Dot artwork; GPL-3.0-or-later. -->\n"
        + "\n".join(paths)
        + "\n</svg>\n"
    )


def points(path: str) -> list[tuple[float, float]]:
    tokens = re.findall(r"[MLCZ]|-?\d*\.?\d+", path)
    result: list[tuple[float, float]] = []
    i = 0
    while i < len(tokens):
        command = tokens[i]
        i += 1
        if command in ("M", "L"):
            result.append((float(tokens[i]), float(tokens[i + 1])))
            i += 2
        elif command == "C":
            start = result[-1]
            coordinates = [float(t) for t in tokens[i : i + 6]]
            i += 6
            for step in range(1, 65):
                t = step / 64
                u = 1 - t
                result.append(
                    tuple(
                        u**3 * start[a]
                        + 3 * u * u * t * coordinates[a]
                        + 3 * u * t * t * coordinates[a + 2]
                        + t**3 * coordinates[a + 4]
                        for a in (0, 1)
                    )
                )
        elif command == "Z":
            result.append(result[0])
        else:
            raise ValueError(f"Unsupported source path command: {command}")
    return result


def render(
    source: str,
    size: int,
    background: str | None = None,
    scale: float = 1,
    monochrome: bool = False,
) -> Image.Image:
    factor = 4 if size <= 1024 else 2
    image = Image.new("RGBA", (size * factor, size * factor), background or (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for path in ET.fromstring(source):
        if path.tag.rsplit("}", 1)[-1] != "path":
            continue
        coords = [
            (
                (50 + (x - 50) * scale) * size * factor / 100,
                (50 + (y - 50) * scale) * size * factor / 100,
            )
            for x, y in points(path.attrib["d"])
        ]
        fill = path.attrib.get("fill", "none")
        if fill != "none":
            # A silhouette with transparent eyes remains recognisable at tray size.
            draw.polygon(
                coords,
                fill=(0, 0, 0, 0)
                if monochrome and fill != PRIMARY
                else "#000000"
                if monochrome
                else fill,
            )
        stroke = path.attrib.get("stroke")
        if stroke:
            width = round(float(path.attrib.get("stroke-width", "1")) * size * factor / 100 * scale)
            color = (0, 0, 0, 0) if monochrome else stroke
            draw.line(coords, fill=color, width=width, joint="curve")
            radius = width / 2
            for x, y in (coords[0], coords[-1]):
                draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def save(image: Image.Image, path: Path, opaque: bool = False) -> None:
    stream = io.BytesIO()
    suffix = path.suffix.lower()
    if suffix == ".webp":
        image.save(stream, format="WEBP", lossless=True, method=6, exact=True)
    elif suffix == ".ico":
        image.save(stream, format="ICO", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    elif suffix == ".icns":
        image.save(stream, format="ICNS")
    else:
        (image.convert("RGB") if opaque else image).save(stream, format="PNG", compress_level=9)
    write(path, stream.getvalue())


def profile(args: argparse.Namespace) -> str:
    global DARK_PRIMARY, CARE, TILE_LIGHT, TILE_DARK, DARK_FACE
    if args.brand_config:
        data = json.loads(Path(args.brand_config).read_text())
        palettes = data.get("themeColors", {})
        DARK_PRIMARY = palettes.get("dark", {}).get("primary", DARK_PRIMARY)
        CARE = palettes.get("light", {}).get("care", CARE)
        TILE_LIGHT = palettes.get("light", {}).get("surface", TILE_LIGHT)
        TILE_DARK = palettes.get("dark", {}).get("background", TILE_DARK)
        DARK_FACE = palettes.get("dark", {}).get("onPrimary", DARK_FACE)
        return palettes.get("light", {}).get("primary", args.primary)
    return args.primary


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    default_config = ROOT / "config/brand.nanodot.json"
    parser.add_argument(
        "--brand-config", type=Path, default=default_config if default_config.exists() else None
    )
    parser.add_argument("--primary", default=PRIMARY)
    return parser.parse_args()


def avatars(primary: str) -> None:
    for state in STATES:
        source = svg(state, primary)
        write(BRAND / f"nanodot-avatar/{state}.svg", source)
        image = render(source, 1024)
        for folder in (
            ROOT / "web/public/avatars",
            ROOT / "harness/dsh-nanomuse/assets",
            IOS / "NanoMuse/Resources",
        ):
            save(image, folder / f"dragon-{state}.webp")
        save(image, RES / f"drawable-nodpi/nm_avatar_{state}.webp")
        save(image, BRAND / f"nanodot-avatar/{state}.png")


def desktop(primary: str) -> None:
    source = svg(primary=primary)
    resources = ROOT / "harness/desktop/resources"
    for name, size in (("app-icon.png", 1024), ("logo.png", 256)):
        save(render(source, size), resources / name)
    for size in (16, 24, 32, 48, 64, 128, 256, 512):
        save(render(source, size), resources / f"icons/{size}x{size}.png")
    save(render(source, 256), resources / "app-icon.ico")
    save(render(source, 1024), resources / "app-icon.icns")
    for size, name in ((18, "trayTemplate.png"), (36, "trayTemplate@2x.png")):
        save(render(svg(), size, monochrome=True), resources / name)
    save(render(source, 22), resources / "tray.png")
    write(BRAND / "nanodot-mark.svg", source)
    for name in ("nanodot-icon.svg", "nanomuse-icon.svg", "nanomuse-mark.svg"):
        write(BRAND / name, source)
    write(BRAND / "nanomuse-icon-on-blue.svg", source)
    save(render(source, 1024), BRAND / "nanomuse-icon-source-1024.png")


def contact_sheet(primary: str) -> None:
    sheet = Image.new("RGB", (900, 360), "#EEF5F2")
    draw = ImageDraw.Draw(sheet)
    for i, state in enumerate(STATES):
        image = render(svg(state, primary), 160)
        sheet.paste(image, (i * 180 + 10, 20), image)
        draw.text((i * 180 + 65, 190), state, fill="#183B35")
    for i, (bg, color) in enumerate(((TILE_LIGHT, primary), (TILE_DARK, DARK_PRIMARY))):
        image = render(svg(primary=color), 112, bg)
        sheet.paste(image.convert("RGB"), (20 + i * 140, 225))
    image = render(svg(), 80, monochrome=True)
    sheet.paste(image, (320, 242), image)
    draw.text((430, 255), "Dot / Nanodot · original vector artwork", fill="#183B35")
    save(sheet, BRAND / "nanodot-contact-sheet.png")


def main() -> None:
    primary = profile(arguments())
    avatars(primary)
    desktop(primary)
    contact_sheet(primary)
    print("Dot avatar, desktop, vector source and contact sheet assets are current.")


if __name__ == "__main__":
    main()
