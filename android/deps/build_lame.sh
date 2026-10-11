#!/bin/bash
set -e

# ============================================================================
# LAME 3.100 Build Script for iOS arm64
# ============================================================================
# Cross-compiles LAME as a static library for use with FFmpeg's libmp3lame
# encoder on iOS.
#
# Usage:
#   ./build_lame.sh [clean]
#
# Output:
# nanoMuse: SDK/architecture outputs, with iphoneos as the default.
#   deps/lame-build/<SDK>/arm64/lib/libmp3lame.a
#   deps/lame-build/<SDK>/arm64/include/lame/lame.h
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAME_VERSION="3.100"
LAME_TARBALL="lame-${LAME_VERSION}.tar.gz"
LAME_SRC_DIR="$SCRIPT_DIR/lame-${LAME_VERSION}"
# nanoMuse: validate SDK before tools/downloads; SDK and architecture are separate.
IOS_TARGET_CONFIG="$(python3 "$SCRIPT_DIR/../../scripts/ios-native-target.py" \
    "${IOS_SDK_PLATFORM-iphoneos}" --arch "${IOS_ARCH-arm64}")" || exit 1
eval "$IOS_TARGET_CONFIG"
IOS_DEPLOYMENT_TARGET="16.0"
LAME_BUILD_DIR="$SCRIPT_DIR/lame-build/$IOS_OUTPUT_SUFFIX/$IOS_ARCH"
LAME_WORK_DIR="$SCRIPT_DIR/build/lame/$IOS_OUTPUT_SUFFIX/$IOS_ARCH"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}ℹ️  $1${NC}"; }
log_success() { echo -e "${GREEN}✅ $1${NC}"; }
log_error()   { echo -e "${RED}❌ $1${NC}"; exit 1; }

# ============================================================================
# Clean
# ============================================================================
if [ "$1" == "clean" ]; then
    log_info "Cleaning LAME build artifacts..."
    # nanoMuse: clean only the selected SDK, retaining the shared source.
    rm -rf "$LAME_WORK_DIR" "$LAME_BUILD_DIR"
    log_success "Clean completed"
    exit 0
fi

# ============================================================================
# Download source
# ============================================================================
if [ ! -d "$LAME_SRC_DIR" ]; then
    log_info "Downloading LAME ${LAME_VERSION}..."
    cd "$SCRIPT_DIR"
    if [ ! -f "$LAME_TARBALL" ]; then
        curl -L -o "$LAME_TARBALL" \
            "https://sourceforge.net/projects/lame/files/lame/${LAME_VERSION}/${LAME_TARBALL}/download"
    fi
    tar xzf "$LAME_TARBALL"
    rm -f "$LAME_TARBALL"
    log_success "LAME source extracted"
else
    log_info "LAME source already present, skipping download"
fi

# ============================================================================
# Cross-compile for iOS arm64
# ============================================================================
log_info "Configuring LAME for iOS arm64..."

IOS_SDK=$(xcrun --sdk "$IOS_SDK_PLATFORM" --show-sdk-path)
CC="$(xcrun --sdk "$IOS_SDK_PLATFORM" -f clang)"

export CC
export CFLAGS="-arch $IOS_ARCH $IOS_MIN_FLAG -isysroot $IOS_SDK -fembed-bitcode -Oz -fPIC -Wno-implicit-function-declaration"
export LDFLAGS="-arch $IOS_ARCH $IOS_MIN_FLAG -isysroot $IOS_SDK"

# nanoMuse: keep configure/object state out of the shared source tree.
mkdir -p "$LAME_WORK_DIR"
cd "$LAME_WORK_DIR"

"$LAME_SRC_DIR/configure" \
    --prefix="$LAME_BUILD_DIR" \
    --host=aarch64-apple-darwin \
    --disable-shared \
    --enable-static \
    --disable-frontend \
    --disable-decoder \
    --disable-gtktest \
    --with-pic

log_info "Building LAME..."
make -j$(sysctl -n hw.ncpu)
make install

cd "$SCRIPT_DIR"

# ============================================================================
# Verify
# ============================================================================
if [ -f "$LAME_BUILD_DIR/lib/libmp3lame.a" ]; then
    log_success "LAME ${LAME_VERSION} built successfully"
    echo ""
    echo "  Static library: $LAME_BUILD_DIR/lib/libmp3lame.a"
    echo "  Headers:        $LAME_BUILD_DIR/include/lame/lame.h"
    echo ""
    file "$LAME_BUILD_DIR/lib/libmp3lame.a"
else
    log_error "Build failed — libmp3lame.a not found"
fi
