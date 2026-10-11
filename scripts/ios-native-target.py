#!/usr/bin/env python3
"""Resolve SDK identity independently of the native slice architecture."""

import argparse
import shlex


def resolve_ios_native_target(platform: str) -> dict[str, str]:
    """Reject unsupported SDKs before any native build/download starts."""
    flags = {
        "iphoneos": "-miphoneos-version-min=16.0",
        "iphonesimulator": "-mios-simulator-version-min=16.0",
    }
    if platform not in flags:
        raise ValueError(f"Unsupported iOS SDK platform: {platform!r}")
    return {"sdk": platform, "minFlag": flags[platform], "outputSuffix": platform}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", nargs="?", default="iphoneos")
    parser.add_argument("--arch", default="arm64")
    args = parser.parse_args()
    try:
        target = resolve_ios_native_target(args.platform)
        # Existing iSH asbestos host and Rclone slices are arm64. Intel simulator
        # support needs a separate native/guest compatibility decision.
        if args.arch != "arm64":
            raise ValueError(f"Unsupported iOS native architecture: {args.arch!r}")
    except ValueError as error:
        parser.error(str(error))
    for name, value in [
        ("IOS_SDK_PLATFORM", target["sdk"]),
        ("IOS_MIN_FLAG", target["minFlag"]),
        ("IOS_OUTPUT_SUFFIX", target["outputSuffix"]),
        ("IOS_ARCH", args.arch),
    ]:
        print(f"{name}={shlex.quote(value)}")


if __name__ == "__main__":
    main()
