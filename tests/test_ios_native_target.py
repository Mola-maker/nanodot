"""Catch SDK mixups by executing the native builders with inert external tools."""

import importlib.util
import json
import os
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
NativeTree = tuple[Path, dict[str, str]]


def resolver() -> Callable[[str], dict[str, str]]:
    path = ROOT / "scripts/ios-native-target.py"
    assert path.exists(), "iOS SDK resolver is missing"
    spec = importlib.util.spec_from_file_location("ios_native_target", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.resolve_ios_native_target


@pytest.mark.parametrize(
    ("platform", "flag"),
    [
        ("iphoneos", "-miphoneos-version-min=16.0"),
        ("iphonesimulator", "-mios-simulator-version-min=16.0"),
    ],
)
def test_resolver_keeps_sdk_and_output_platform_distinct(platform: str, flag: str) -> None:
    assert resolver()(platform) == {"sdk": platform, "minFlag": flag, "outputSuffix": platform}


@pytest.mark.parametrize("platform", ["", "macosx", "iPhoneOS", "iphoneos; echo wrong"])
def test_resolver_rejects_unknown_sdk(platform: str) -> None:
    with pytest.raises(ValueError):
        resolver()(platform)


# External build tools are inert because Linux has no Apple SDK. Actual builder
# control flow, configuration files, filesystem output and command arguments run.
TOOL = r"""#!/usr/bin/env python3
import ast, json, os, pathlib, subprocess, sys
p = pathlib.Path
name = p(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['COMMAND_LOG'], 'a') as f:
    f.write(json.dumps({'tool': name, 'args': args, 'cwd': os.getcwd(),
                        'cflags': os.environ.get('CFLAGS'),
                        'ldflags': os.environ.get('LDFLAGS')}) + '\n')
if name == 'xcrun':
    sdk = args[args.index('--sdk') + 1]
    print('/fixture/sdk/' + sdk if '--show-sdk-path' in args else os.environ['FAKE_BIN'] + '/clang')
elif name == 'sysctl':
    print('2')
elif name == 'configure':
    prefix = next(a.split('=', 1)[1] for a in args if a.startswith('--prefix='))
    p('.fixture-prefix').write_text(prefix)
    flags = os.environ.get('CFLAGS', '').split()
    if '--enable-cross-compile' in args:
        flags = next(a.split('=', 1)[1] for a in args if a.startswith('--extra-cflags=')).split()
        flags += ['-isysroot', next(a.split('=', 1)[1] for a in args if a.startswith('--sysroot='))]
    subprocess.run([os.environ['FAKE_BIN'] + '/clang', *flags], check=True)
elif name == 'make' and args == ['install']:
    dest = p('.fixture-prefix').read_text()
    p(dest + '/lib').mkdir(parents=True, exist_ok=True)
    if 'lame' in os.getcwd():
        p(dest + '/lib/libmp3lame.a').write_text('inert fixture')
        p(dest + '/include/lame').mkdir(parents=True, exist_ok=True)
        p(dest + '/include/lame/lame.h').touch()
    else:
        for lib in ['avcodec', 'avformat', 'avutil', 'avfilter', 'swresample', 'swscale']:
            p(dest + '/lib/lib' + lib + '.1.2.3.dylib').write_text('inert fixture')
            inc = p(dest + '/include/lib' + lib)
            inc.mkdir(parents=True, exist_ok=True)
            (inc / 'fixture.h').touch()
elif name == 'clang' and '-o' in args:
    dest = p(args[args.index('-o') + 1])
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text('inert fixture')
elif name == 'meson' and args[0] == 'setup':
    build = p(args[1])
    cross = p(args[args.index('--cross-file') + 1]).read_text()
    compiler = ast.literal_eval(next(line[4:] for line in cross.splitlines() if line.startswith('c = ')))
    subprocess.run(compiler, check=True)
    (build / 'build.ninja').touch()
elif name == 'ninja':
    build = p(args[args.index('-C') + 1])
    for target in args[args.index('-C') + 2:]:
        dest = build / target
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text('inert fixture')
elif name == 'otool':
    print(args[-1] + ':\n@rpath/' + p(args[-1]).name)
elif name == 'nm':
    print('ffmpeg_main')
elif name in ['curl', 'tar', 'git']:
    sys.exit('unexpected external operation: ' + name)
"""


@pytest.fixture
def native_tree(tmp_path: Path) -> NativeTree:
    deps = tmp_path / "android/deps"
    deps.mkdir(parents=True)
    (tmp_path / "scripts").mkdir()
    for name in ["build_lame.sh", "build_ffmpeg.sh", "build_ish.sh"]:
        shutil.copy(ROOT / "android/deps" / name, deps / name)
    resolver_path = ROOT / "scripts/ios-native-target.py"
    if resolver_path.exists():
        shutil.copy(resolver_path, tmp_path / "scripts/ios-native-target.py")
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    for name in [
        "xcrun",
        "clang",
        "sysctl",
        "make",
        "xcode-select",
        "meson",
        "ninja",
        "install_name_tool",
        "codesign",
        "otool",
        "nm",
        "ar",
        "curl",
        "tar",
        "git",
    ]:
        tool = bin_dir / name
        tool.write_text(TOOL)
        tool.chmod(0o755)
    for source in ["lame-3.100", "ffmpeg-6.1.2"]:
        src = deps / source
        src.mkdir()
        (src / "configure").write_text(TOOL)
        (src / "configure").chmod(0o755)
    (deps / "ffmpeg-6.1.2/fftools").mkdir()
    (deps / "ffmpeg-6.1.2/fftools/ffmpeg.c").write_text("int ffmpeg_main(\n")
    (deps / "ffmpeg-patch").mkdir()
    for name in ["fftools_stdio_redirect.c", "fftools_stdio_redirect.h"]:
        (deps / "ffmpeg-patch" / name).touch()
    ish = deps / "ish"
    for folder in ["deps/libapps/.git", "emu", "kernel", "fs", "util", "platform", "asbestos"]:
        (ish / folder).mkdir(parents=True)
        (ish / folder / "fixture.h").touch()
    for name in ["debug.h", "misc.h", "xX_main_Xx.h"]:
        (ish / name).touch()
    env = os.environ.copy()
    env.pop("IOS_SDK_PLATFORM", None)
    env.pop("IOS_ARCH", None)
    (tmp_path / "tmp").mkdir()
    env.update(
        TMPDIR=str(tmp_path / "tmp"),
        PATH=f"{bin_dir}:{env['PATH']}",
        FAKE_BIN=str(bin_dir),
        COMMAND_LOG=str(tmp_path / "commands.jsonl"),
    )
    return deps, env


def run_builder(
    native_tree: NativeTree,
    name: str,
    sdk: str | None = None,
    arch: str | None = None,
    action: str | None = None,
) -> tuple[subprocess.CompletedProcess[str], list[dict]]:
    deps, env = native_tree
    env = env.copy()
    if sdk is not None:
        env["IOS_SDK_PLATFORM"] = sdk
    if arch is not None:
        env["IOS_ARCH"] = arch
    args = ["bash", str(deps / f"build_{name}.sh")]
    if action:
        args.append(action)
    result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=60)
    log = Path(env["COMMAND_LOG"])
    commands = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
    return result, commands


@pytest.mark.parametrize("builder", ["lame", "ffmpeg", "ish"])
@pytest.mark.parametrize("sdk", [None, "iphoneos", "iphonesimulator"])
def test_builders_execute_correct_sdk_commands_and_isolate_outputs(
    native_tree: NativeTree,
    builder: str,
    sdk: str | None,
) -> None:
    # A hardcoded device sysroot/minflag, shared output or lost default fails here.
    deps, _ = native_tree
    platform = sdk or "iphoneos"
    result, commands = run_builder(native_tree, builder, sdk)
    assert result.returncode == 0, result.stdout + result.stderr
    xcrun = [c["args"] for c in commands if c["tool"] == "xcrun"]
    assert xcrun and all(a[a.index("--sdk") + 1] == platform for a in xcrun)
    flag = (
        "-miphoneos-version-min=16.0"
        if platform == "iphoneos"
        else "-mios-simulator-version-min=16.0"
    )
    clang = [c["args"] for c in commands if c["tool"] == "clang"]
    assert clang
    for args in clang:
        assert flag in args
        assert args[args.index("-isysroot") + 1] == f"/fixture/sdk/{platform}"
        assert args[args.index("-arch") + 1] == "arm64"
    outputs = {
        "lame": f"lame-build/{platform}/arm64/lib/libmp3lame.a",
        "ffmpeg": f"frameworks/{platform}/arm64/FFmpeg.framework/FFmpeg",
        "ish": f"libs/{platform}/arm64/libish.a",
    }
    assert (deps / outputs[builder]).is_file()
    if builder == "ish":
        meson = next(c for c in commands if c["tool"] == "meson" and c["args"][0] == "setup")
        assert meson["args"][1] == str(deps / f"build/ish/{platform}/arm64")
    if builder == "ffmpeg":
        import plistlib

        with (deps / f"frameworks/{platform}/arm64/FFmpeg.framework/Info.plist").open("rb") as f:
            info = plistlib.load(f)
        assert info["MinimumOSVersion"] == "16.0"
        assert info["CFBundleSupportedPlatforms"] == [
            "iPhoneOS" if platform == "iphoneos" else "iPhoneSimulator"
        ]


@pytest.mark.parametrize("builder", ["lame", "ffmpeg", "ish"])
@pytest.mark.parametrize("sdk,arch", [("macosx", None), ("", None), (None, "x86_64")])
def test_builders_reject_unsupported_target_before_tools_or_writes(
    native_tree: NativeTree,
    builder: str,
    sdk: str | None,
    arch: str | None,
) -> None:
    result, commands = run_builder(native_tree, builder, sdk, arch)
    assert result.returncode != 0
    assert not commands, "target rejection must precede external toolchain/source work"


def test_simulator_ffmpeg_uses_matching_lame_and_separate_configure_cache(
    native_tree: NativeTree,
) -> None:
    deps, _ = native_tree
    for sdk in ["iphoneos", "iphonesimulator"]:
        result, _ = run_builder(native_tree, "lame", sdk)
        assert result.returncode == 0, result.stderr
        result, commands = run_builder(native_tree, "ffmpeg", sdk)
        assert result.returncode == 0, result.stdout + result.stderr
        configure = [c for c in commands if c["tool"] == "configure" and "ffmpeg" in c["cwd"]][-1]
        assert "--enable-libmp3lame" in configure["args"]
        assert any(f"-L{deps}/lame-build/{sdk}/arm64/lib" in a for a in configure["args"])
        assert configure["cwd"] == str(deps / f"ffmpeg-6.1.2/build-{sdk}-arm64")
    assert (deps / "frameworks/iphoneos/arm64/FFmpeg.framework/FFmpeg").is_file()
    assert (deps / "frameworks/iphonesimulator/arm64/FFmpeg.framework/FFmpeg").is_file()


def test_clean_removes_only_selected_target(native_tree: NativeTree) -> None:
    deps, _ = native_tree
    for sdk in ["iphoneos", "iphonesimulator"]:
        result, _ = run_builder(native_tree, "lame", sdk)
        assert result.returncode == 0, result.stderr
    result, _ = run_builder(native_tree, "lame", "iphonesimulator", action="clean")
    assert result.returncode == 0
    assert (deps / "lame-build/iphoneos/arm64/lib/libmp3lame.a").is_file()
    assert not (deps / "lame-build/iphonesimulator/arm64").exists()


@pytest.mark.parametrize(
    "fault", [None, "device-as-simulator", "wrong-arch", "duplicate", "missing"]
)
def test_workflow_platform_gate_executes_and_rejects_invalid_slice(
    native_tree: NativeTree,
    fault: str | None,
) -> None:
    # Execute the actual workflow's shell gate; a wrong vtool SDK, skipped
    # archive object, CPU-only check or missing output must fail before caching.
    import yaml

    deps, env = native_tree
    workflow = yaml.safe_load((ROOT / ".github/workflows/ios-check.yml").read_text())
    step = next(
        s
        for s in workflow["jobs"]["build"]["steps"]
        if s.get("name") == "Validate every native Mach-O slice before caching or linking"
    )
    bin_dir = Path(env["FAKE_BIN"])
    verifier_tool = r"""#!/usr/bin/env python3
import os, pathlib, sys
p = pathlib.Path
name = p(sys.argv[0]).name
args = sys.argv[1:]
if name == 'ar':
    marker = p(args[-1]).read_text()
    if args[0] == '-t':
        print('__.SYMDEF\nfixture.o')
        if 'DUP' in marker:
            print('fixture.o')
    elif args[0] == '-x':
        p('fixture.o').write_text(marker)
        p('__.SYMDEF').touch()
elif name == 'lipo':
    print(p(args[-1]).read_text().split()[1])
elif name == 'xcrun':
    assert args[:2] == ['vtool', '-show-build']
    print('platform ' + p(args[-1]).read_text().split()[0])
"""
    for name in ["ar", "lipo", "xcrun"]:
        tool = bin_dir / name
        tool.write_text(verifier_tool)
        tool.chmod(0o755)
    for sdk, platform in [("iphoneos", "IOS"), ("iphonesimulator", "IOSSIMULATOR")]:
        for lib in ["ish", "ish_emu", "fakefs"]:
            binary = deps / f"libs/{sdk}/arm64/lib{lib}.a"
            binary.parent.mkdir(parents=True, exist_ok=True)
            binary.write_text(f"{platform} arm64")
        lame = deps / f"lame-build/{sdk}/arm64/lib/libmp3lame.a"
        lame.parent.mkdir(parents=True)
        lame.write_text(f"{platform} arm64")
        for lib in [
            "FFmpeg",
            "libavcodec",
            "libavformat",
            "libavutil",
            "libavfilter",
            "libswresample",
            "libswscale",
        ]:
            binary = deps / f"frameworks/{sdk}/arm64/{lib}.framework/{lib}"
            binary.parent.mkdir(parents=True)
            binary.write_text(f"{platform} arm64")
        (deps / f"resources/{sdk}/arm64/RootfsPatch.bundle").mkdir(parents=True)
        tag = "ios-arm64" + ("-simulator" if sdk == "iphonesimulator" else "")
        rclone = deps / f"frameworks/Rclone.xcframework/{tag}/librclone.a"
        rclone.parent.mkdir(parents=True)
        rclone.write_text(f"{platform} arm64")
    (deps / "resources/alpine-rootfs").mkdir()
    (deps / "resources/alpine-rootfs.zip").write_text("inert rootfs fixture")
    scratch_root = deps.parents[1]
    (scratch_root / "android/src/ios/build/unsigned").mkdir(parents=True)
    candidate = deps / "libs/iphonesimulator/arm64/libish_emu.a"
    if fault == "device-as-simulator":
        candidate.write_text("IOS arm64")
    elif fault == "wrong-arch":
        candidate.write_text("IOSSIMULATOR x86_64")
    elif fault == "duplicate":
        candidate.write_text("IOSSIMULATOR arm64 DUP")
    elif fault == "missing":
        candidate.unlink()
    result = subprocess.run(
        ["bash", "-c", step["run"]],
        cwd=scratch_root,
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert (result.returncode == 0) == (fault is None), result.stdout + result.stderr
