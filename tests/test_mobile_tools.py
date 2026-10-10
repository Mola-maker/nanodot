"""A stale binary on PATH cannot bypass the native generator version pin."""

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_mobile_tools_use_verified_local_versions(tmp_path: Path) -> None:
    tools = tmp_path / "tools"
    tools.mkdir()
    for name in ("gomobile", "gobind"):
        binary = tools / name
        binary.write_text("#!/bin/sh\necho stale\n")
        binary.chmod(0o755)
    fake_go = tools / "go"
    fake_go.write_text(
        "#!/usr/bin/env python3\n"
        "import os, pathlib, sys\n"
        "if sys.argv[1] == 'version':\n"
        "    marker = pathlib.Path(sys.argv[-1] + '.version')\n"
        "    version = marker.read_text() if marker.exists() else 'old'\n"
        "    print('mod golang.org/x/mobile ' + version)\n"
        "elif sys.argv[1] == 'install':\n"
        "    package, version = sys.argv[-1].split('@')\n"
        "    binary = pathlib.Path(os.environ['GOBIN']) / package.rsplit('/', 1)[1]\n"
        "    binary.parent.mkdir(parents=True, exist_ok=True)\n"
        "    binary.write_text('#!/bin/sh\\necho pinned\\n')\n"
        "    binary.chmod(0o755)\n"
        "    pathlib.Path(str(binary) + '.version').write_text(version)\n"
    )
    fake_go.chmod(0o755)
    env = {
        **os.environ,
        "PATH": f"{tools}:{os.environ['PATH']}",
        "GOPATH": str(tmp_path / "go-cache"),
        "NANODOT_GOMOBILE_VERSION": "v0.0.0-testpin",
    }
    result = subprocess.run(
        [
            "bash",
            "-c",
            'source "$1"; ensure_nanodot_mobile_tools; gomobile; gobind',
            "test",
            str(ROOT / "scripts/android/mobile-tools.sh"),
        ],
        capture_output=True,
        text=True,
        env=env,
        timeout=10,
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == ["pinned", "pinned"]
