"""Bounded Android profile validation and local candidate receipts (no publishing)."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def run(*args: str, cwd: Path | None = None) -> str:
    return subprocess.check_output(args, cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()


def profile(name: str, paired: bool) -> tuple[str, str, list[str], bool]:
    mode = "release" if name == "release" else "debug"
    abi = "x86_64" if name in ("debug-x86_64", "check") else "arm64-v8a"
    if paired and name in ("release", "check", "doctor"):
        raise ValueError("--with-test requires a debug APK profile")
    tasks = [":app:testDebugUnitTest"] if name == "check" else [f":app:assemble{mode.title()}"]
    if paired:
        tasks.append(":app:assembleDebugAndroidTest")
    return mode, abi, tasks, name in ("debug", "release")


def gradle_options(env: dict) -> list[str]:
    result = ["-Pnm.manualVoiceProof=false"]
    scope = env.get("NANODOT_TEST_SCOPE", "all")
    if scope not in ("all", "nanomuse"):
        raise ValueError("NANODOT_TEST_SCOPE must be all or nanomuse")
    if scope == "nanomuse":
        result += ["--tests", "io.github.nanomuse.*"]
    for key in ("NANODOT_OFFLINE", "NANODOT_KOTLIN_IN_PROCESS"):
        if env.get(key, "0") not in ("0", "1"):
            raise ValueError(f"{key} must be 0 or 1")
    if env.get("NANODOT_OFFLINE") == "1":
        result.append("--offline")
    heap = env.get("NANODOT_JVM_HEAP_MB")
    if heap:
        if not heap.isdigit() or not 512 <= int(heap) <= 32768:
            raise ValueError("NANODOT_JVM_HEAP_MB must be 512..32768")
        result.append(f"-Dorg.gradle.jvmargs=-Xmx{heap}m -Dfile.encoding=UTF-8")
    if env.get("NANODOT_KOTLIN_IN_PROCESS") == "1":
        result.append("-Pkotlin.compiler.execution.strategy=in-process")
    return result


def validate_java(value: str) -> None:
    if not re.search(r'version "21[.\"]', value):
        raise ValueError("JDK 21 required")


def parse_badging(value: str, *, test_apk: bool = False) -> dict:
    result = {}
    for field, pattern in {
        "applicationId": r"package: name='([^']+)'",
        "versionCode": r"versionCode='(\d+)'",
        "versionName": r"versionName='([^']+)'",
        "minSdk": r"sdkVersion:'(\d+)'",
        "targetSdk": r"targetSdkVersion:'(\d+)'",
    }.items():
        match = re.search(pattern, value)
        if not match and test_apk and field in ("versionCode", "versionName"):
            result[field] = None
            continue
        if not match:
            raise ValueError(f"Missing APK {pattern.split(':')[0]}")
        result[field] = (
            int(match[1]) if field in ("versionCode", "minSdk", "targetSdk") else match[1]
        )
    native = re.search(r"^native-code: (.+)$", value, re.M)
    result["abis"] = re.findall(r"'([^']+)'", native[1]) if native else []
    return result


def validate_artifact(value: dict, package: str, abi: str) -> None:
    if value["applicationId"] != package:
        raise ValueError("APK applicationId mismatch")
    if value["abis"] != [abi]:
        raise ValueError(f"APK ABI mismatch: expected {abi}, got {value['abis']}")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source(root: Path) -> dict:
    tracked = run("git", "ls-files", "-z", cwd=root).split("\0")
    untracked = run("git", "ls-files", "--others", "--exclude-standard", "-z", cwd=root).split("\0")
    digest = hashlib.sha256()
    for name in sorted(set(tracked + untracked)):
        if name and (root / name).is_file():
            digest.update(name.encode() + b"\0" + (root / name).read_bytes())
    return {
        "sourceCommit": run("git", "rev-parse", "HEAD", cwd=root),
        "sourceTree": run("git", "rev-parse", "HEAD^{tree}", cwd=root),
        "sourceDirty": bool(run("git", "status", "--porcelain", cwd=root)),
        "sourceFingerprint": digest.hexdigest(),
    }


def doctor(root: Path) -> dict:
    sdk = Path(os.environ.get("ANDROID_HOME", ""))
    if not os.environ.get("ANDROID_HOME"):
        raise ValueError("Set ANDROID_HOME to the Android SDK")
    java = run("java", "-version")
    validate_java(java)
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", str(sdk / "ndk/27.2.12479018")))
    props = ndk / "source.properties"
    if not props.is_file() or not re.search(
        r"Pkg.Revision\s*=\s*27\.2\.12479018", props.read_text()
    ):
        raise ValueError("NDK r27c (27.2.12479018) required")
    cmake = sdk / "cmake/3.22.1/bin/cmake"
    tools = sdk / "build-tools/35.0.0"
    for path in (
        sdk / "platforms/android-36/android.jar",
        cmake,
        tools / "aapt",
        tools / "apksigner",
        root / "android/src/android/gradlew",
    ):
        if not path.is_file():
            raise ValueError(f"Missing required tool/file: {path}")
    if "3.22.1" not in run(str(cmake), "--version"):
        raise ValueError("CMake 3.22.1 required")
    go = subprocess.check_output(
        ["go", "version"],
        text=True,
        stderr=subprocess.STDOUT,
        env={**os.environ, "GOTOOLCHAIN": "local"},
    ).strip()
    if not re.search(r"go1\.26[. ]", go):
        raise ValueError("Go 1.26 required")
    wrapper = (root / "android/src/android/gradle/wrapper/gradle-wrapper.properties").read_text()
    if "gradle-8.11.1-bin.zip" not in wrapper:
        raise ValueError("Gradle wrapper 8.11.1 required")
    build_props = tools / "source.properties"
    if not build_props.is_file() or not re.search(
        r"Pkg.Revision\s*=\s*35\.0\.0", build_props.read_text()
    ):
        raise ValueError("Android build-tools 35.0.0 required")
    return {
        "java": java,
        "go": go,
        "ndk": props.read_text().strip(),
        "cmake": run(str(cmake), "--version"),
        "buildTools": str(tools),
        "buildToolsRevision": build_props.read_text().strip(),
        "gradleWrapper": (
            root / "android/src/android/gradle/wrapper/gradle-wrapper.properties"
        ).read_text(),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["doctor", "receipt", "options"])
    parser.add_argument("--profile", default="debug")
    parser.add_argument("--with-test", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if args.action == "options":
        print("\n".join(gradle_options(dict(os.environ))))
        return
    toolchain = doctor(root)
    if args.action == "doctor":
        print(json.dumps(toolchain, indent=2))
        return
    mode, abi, tasks, _ = profile(args.profile, args.with_test)
    identity_before = json.loads(os.environ["NANODOT_SOURCE_IDENTITY"])
    if source(root) != identity_before:
        raise ValueError("Source changed during build; rebuild a stable candidate")
    output = root / "dist/nanodot"
    output.mkdir(parents=True, exist_ok=True)
    candidate = Path(
        tempfile.mkdtemp(
            prefix=f"{identity_before['sourceCommit'][:12]}-{args.profile}-{abi}-", dir=output
        )
    )
    brand = json.loads((root / "config/brand.nanodot.json").read_text())
    artifacts = []
    for kind in ["main", "test"] if args.with_test else ["main"]:
        apk = (
            root
            / "android/src/android/app/build/outputs/apk"
            / (
                f"{mode}/app-{mode}.apk"
                if kind == "main"
                else "androidTest/debug/app-debug-androidTest.apk"
            )
        )
        badging = run(str(Path(toolchain["buildTools"]) / "aapt"), "dump", "badging", str(apk))
        metadata = parse_badging(badging, test_apk=kind == "test")
        if kind == "main":
            validate_artifact(metadata, brand["androidApplicationId"], abi)
        elif metadata["applicationId"] != brand["androidApplicationId"] + ".test":
            raise ValueError("Test APK applicationId mismatch")
        signature = run(
            str(Path(toolchain["buildTools"]) / "apksigner"), "verify", "--print-certs", str(apk)
        )
        cert = re.search(r"Signer #1 certificate SHA-256 digest: (\S+)", signature)
        if not cert:
            raise ValueError("Missing signing certificate SHA-256")
        filename_version = (
            metadata["versionName"] if kind == "main" else artifacts[0]["versionName"]
        )
        name = f"Nanodot-{identity_before['sourceCommit'][:12]}-{filename_version}-{args.profile}-{abi}-{kind}.apk"
        shutil.copy2(apk, candidate / name)
        artifacts.append(
            {
                **metadata,
                "kind": kind,
                "file": name,
                "sha256": sha(apk),
                "certificateSha256": cert[1],
                "signature": signature,
            }
        )
        if kind == "main" and args.profile in ("debug", "release"):
            legacy = output / f"Nanodot-{metadata['versionName']}-{mode}-arm64-v8a.apk"
            shutil.copy2(apk, legacy)
            (output / f"{legacy.name}.sha256").write_text(f"{sha(apk)}  {legacy.name}\n")
            (output / f"signature-{mode}.txt").write_text(signature + "\n")
            (output / f"badging-{mode}.txt").write_text(badging + "\n")
    if (
        len(artifacts) == 2
        and artifacts[0]["certificateSha256"] != artifacts[1]["certificateSha256"]
    ):
        raise ValueError("Main/test signing certificate mismatch")
    cache = root / "android/src/android/app/src/main"
    native = {str(p.relative_to(root)): sha(p) for p in sorted(cache.glob("jniLibs/**/*.so"))}
    for p in [
        cache / "assets/proot-aarch64",
        cache / "assets/alpine-minirootfs.tar.gz",
        root / "android/src/android/app/libs/rclone.aar",
    ]:
        if p.is_file():
            native[str(p.relative_to(root))] = sha(p)
    receipt = {
        **identity_before,
        "profile": args.profile,
        "abi": abi,
        "buildType": mode,
        "tasks": tasks,
        "gradleOptions": gradle_options(dict(os.environ)),
        "diagnosticFlags": [f"-Pnm.abi={abi}", "-Pnm.manualVoiceProof=false"],
        "toolchain": toolchain,
        "nativeCache": {
            "provenance": "unverified local inputs; hashes identify bytes only",
            "sha256": native,
        },
        "artifacts": artifacts,
    }
    (candidate / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if args.profile in ("debug", "release"):
        (output / f"build-{mode}.json").write_text(
            json.dumps(
                {
                    **receipt,
                    **artifacts[0],
                    "file": legacy.name,
                    "candidateReceipt": str((candidate / "receipt.json").relative_to(output)),
                },
                indent=2,
            )
            + "\n"
        )
    print(candidate / "receipt.json")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit(str(exc)) from exc
