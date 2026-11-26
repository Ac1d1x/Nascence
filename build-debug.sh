#!/usr/bin/env bash
# Build the Nascence debug APK on this aarch64 host.
#
# The Android SDK/NDK toolchains are x86_64 binaries, so the build relies on
# binfmt_misc + qemu to run them. Two machine-specific fixes are applied
# idempotently below:
#   1. QEMU_LD_PREFIX so the x86_64 dynamic loader finds its libraries.
#   2. NDK host detection: teach ndk-build about aarch64 and provide
#      linux-arm64 -> linux-x86_64 symlinks for its prebuilt tools.
set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-/usr/x86_64-linux-gnu}"

if [ "$(uname -m)" = "aarch64" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_VERSION="${JITPACK_NDK_VERSION:-}"
    if [ -z "$NDK_VERSION" ]; then
        # Pick the newest NDK that is actually installed (skip empty dirs AGP may create).
        for candidate in $(ls -1 "$ANDROID_HOME/ndk" | sort -rV); do
            if [ -f "$ANDROID_HOME/ndk/$candidate/build/tools/ndk_bin_common.sh" ]; then
                NDK_VERSION="$candidate"
                break
            fi
        done
    fi
    if [ -z "$NDK_VERSION" ]; then
        echo "No usable NDK found under $ANDROID_HOME/ndk" >&2
        exit 1
    fi
    export JITPACK_NDK_VERSION="$NDK_VERSION"
    NDK_DIR="$ANDROID_HOME/ndk/$NDK_VERSION"
    NDK_COMMON="$NDK_DIR/build/tools/ndk_bin_common.sh"

    if [ -f "$NDK_COMMON" ] && ! grep -q 'aarch64) HOST_ARCH=arm64' "$NDK_COMMON"; then
        sed -i 's/^  arm64) HOST_ARCH=arm64;;/  arm64|aarch64) HOST_ARCH=arm64;;/' "$NDK_COMMON"
    fi
    ln -sfn linux-x86_64 "$NDK_DIR/prebuilt/linux-arm64"
    mkdir -p "$NDK_DIR/toolchains/llvm/prebuilt"
    ln -sfn linux-x86_64 "$NDK_DIR/toolchains/llvm/prebuilt/linux-arm64"
fi

./gradlew assembleDebug "$@"

APK="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"
mkdir -p dist
cp -f "$APK" "dist/Nascence-debug-arm64-v8a.apk"

echo
echo "APK: $(pwd)/dist/Nascence-debug-arm64-v8a.apk"
sha256sum "dist/Nascence-debug-arm64-v8a.apk"
