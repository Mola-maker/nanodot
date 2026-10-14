#!/usr/bin/env python3
"""Export the reviewed hierarchy to a flat directory for multi-file upload.
No network calls. Asset bytes are preserved. Only resolved local URL references
and explicit deployment metadata are adapted; the canonical site is untouched.
"""

import hashlib
import json
import posixpath
import re
import shutil
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit, urlunsplit

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "site"
DEST = ROOT / "deploy-flat"
files = sorted(p for p in SOURCE.rglob("*") if p.is_file())
mapping = {
    p.relative_to(SOURCE).as_posix(): p.relative_to(SOURCE).as_posix().replace("/", "--")
    for p in files
}
assert len(set(mapping.values())) == len(mapping), "Flat-name collision"
DEST.mkdir(exist_ok=True)
expected = set(mapping.values()) | {"deployment-map.json"}
for old in DEST.iterdir():
    if old.is_file() and old.name not in expected:
        raise RuntimeError(f"Unexpected old deployment file: {old.name}; refusing deletion")
changes = []


def local_url(url: str, source: str) -> str:
    parsed = urlsplit(url)
    if parsed.scheme or parsed.netloc or not parsed.path or url.startswith("#"):
        return url
    original_path = unquote(parsed.path)
    path = (
        posixpath.normpath(posixpath.join(posixpath.dirname(source), original_path))
        if not original_path.startswith("/")
        else original_path.lstrip("/")
    )
    if path == ".":
        path = ""
    if path not in mapping and ((SOURCE / path).is_dir() or original_path.endswith("/")):
        path = (path.rstrip("/") + "/" if path else "") + "index.html"
    if path not in mapping:
        raise RuntimeError(f"Unresolved local URL {url!r} in {source}")
    updated = urlunsplit(("", "", mapping[path], parsed.query, parsed.fragment))
    if updated != url:
        changes.append({"file": source, "from": url, "to": updated})
    return updated


html_attr = re.compile(r"\b(href|src|poster)\s*=\s*([\"\'])(.*?)\2", re.I | re.S)
css_url = re.compile(r"url\(\s*([\"\']?)([^)\"\']+)\1\s*\)", re.I)
for source, flat in mapping.items():
    original = SOURCE / source
    destination = DEST / flat
    if original.suffix == ".html":
        s = original.read_text(encoding="utf-8")
        s = html_attr.sub(
            lambda m, source=source: (
                m.group(1) + "=" + m.group(2) + local_url(m.group(3), source) + m.group(2)
            ),
            s,
        )
        destination.write_text(s, encoding="utf-8", newline="\n")
    elif original.suffix == ".css":
        s = original.read_text(encoding="utf-8")
        s = css_url.sub(
            lambda m, source=source: (
                "url(" + m.group(1) + local_url(m.group(2).strip(), source) + m.group(1) + ")"
            ),
            s,
        )
        destination.write_text(s, encoding="utf-8", newline="\n")
    else:
        shutil.copyfile(original, destination)
# JS contains only local selectors, inline data, and fully qualified network URLs.
# Fail if a new quoted path to a known local file appears and needs explicit review.
for source in mapping:
    if source.endswith(".js"):
        s = (SOURCE / source).read_text(encoding="utf-8")
        for literal in re.findall(r"[\"\']([^\"\'\n]+)[\"\']", s):
            u = urlsplit(literal)
            if u.scheme or u.netloc:
                continue
            p = posixpath.normpath(posixpath.join(posixpath.dirname(source), u.path))
            assert p not in mapping, (
                f"New JS local file reference requires an explicit rewrite: {source}: {literal}"
            )
# Preserve canonical provenance and add the deployed-file identity separately.
p = DEST / mapping["upstream-demo/source-manifest.json"]
manifest = json.loads(p.read_text(encoding="utf-8"))
manifest["deploymentLayout"] = (
    "flat export; original hashes remain canonical hierarchical-source hashes"
)
for record in manifest["files"]:
    canonical = "upstream-demo/" + record["path"]
    name = mapping[canonical]
    record["deployedFile"] = name
    record["sha256Deployed"] = hashlib.sha256((DEST / name).read_bytes()).hexdigest()
p.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
)
p = DEST / "manifest.json"
manifest = json.loads(p.read_text(encoding="utf-8"))
manifest["evidence"]["file"] = mapping[manifest["evidence"]["file"]]
manifest["upstream"]["localPath"] = mapping["upstream-demo/index.html"]
manifest["deploymentLayout"] = "flat; see deployment-map.json for canonical source paths"
p.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
)
p = DEST / "README.md"
s = p.read_text(encoding="utf-8")
s += """\n## 当前上传包：扁平导出\n\n本目录用于魔搭浏览器多文件上传。上传这里的全部文件到创空间根目录，`index.html` 仍是入口。层级源码在 GitHub 保持原结构；此部署包把路径 `/` 映射为文件名中的 `--`（例如 `upstream-demo--index.html`）。HTML 与 CSS 的本地引用已经同步改写，视频、图片等二进制文件字节保持原样。准确映射和导出哈希见 `deployment-map.json`。上文 `assets/`、`upstream-demo/` 指规范源码中的目录。\n"""
p.write_text(s, encoding="utf-8", newline="\n")


class Parser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.urls: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.urls.extend(v for k, v in attrs if k in {"href", "src", "poster"} and v)


checked = 0
for file in DEST.iterdir():
    urls = []
    if file.suffix == ".html":
        parser = Parser()
        parser.feed(file.read_text(encoding="utf-8"))
        urls = parser.urls
    elif file.suffix == ".css":
        urls = [m.group(2).strip() for m in css_url.finditer(file.read_text(encoding="utf-8"))]
    for url in urls:
        u = urlsplit(url)
        if u.scheme or u.netloc or not u.path:
            continue
        assert "/" not in u.path, (file.name, url)
        assert (DEST / unquote(u.path)).is_file(), (file.name, url)
        checked += 1
records = []
for source, flat in mapping.items():
    records.append(
        {
            "source": source,
            "deployed": flat,
            "sha256Source": hashlib.sha256((SOURCE / source).read_bytes()).hexdigest(),
            "sha256Deployed": hashlib.sha256((DEST / flat).read_bytes()).hexdigest(),
            "bytesDeployed": (DEST / flat).stat().st_size,
        }
    )
(DEST / "deployment-map.json").write_text(
    json.dumps(
        {
            "formatVersion": 1,
            "generatedFrom": "canonical site/ source",
            "date": "2026-10-14",
            "entrypoint": "index.html",
            "validatedLocalUrls": checked,
            "files": records,
            "rewrittenReferences": changes,
        },
        ensure_ascii=False,
        indent=2,
    )
    + "\n",
    encoding="utf-8",
    newline="\n",
)
print(
    json.dumps(
        {
            "sourceFiles": len(files),
            "deployedFiles": len(list(DEST.iterdir())),
            "validatedLocalUrls": checked,
            "rewrittenReferences": len(changes),
            "output": str(DEST),
        },
        ensure_ascii=True,
    )
)
