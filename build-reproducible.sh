#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/mnt/data/offline_env/APKForge-Offline-Dev-Linux-x86_64/android-sdk}"
JAVA_HOME="${JAVA_HOME:-/mnt/data/offline_env/APKForge-Offline-Dev-Linux-x86_64/tools/jdk-21}"
GRADLE_BIN="${GRADLE_BIN:-/mnt/data/offline_env/APKForge-Offline-Dev-Linux-x86_64/tools/gradle/gradle-9.5.1/bin/gradle}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"

: "${GITHUB_CLIENT_ID:?Set GITHUB_CLIENT_ID before building}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" JAVA_HOME
export PATH="$JAVA_HOME/bin:$(dirname "$GRADLE_BIN"):$PATH"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1767225600}"

cd "$ROOT"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
"$GRADLE_BIN" --offline --no-daemon clean assembleRelease assembleDebugAndroidTest

UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="$OUT_DIR/GitHubble_0.26.0-beta-stage73-84_aligned-unsigned.apk"
"$BUILD_TOOLS/zipalign" -p -f 4 "$UNSIGNED" "$ALIGNED"

if [[ -n "${GITHUBBLE_KEYSTORE:-}" ]]; then
  : "${GITHUBBLE_STORE_PASSWORD:?Set GITHUBBLE_STORE_PASSWORD for signing}"
  ALIAS="${GITHUBBLE_KEY_ALIAS:-githubble-release}"
  export GITHUBBLE_STORE_PASSWORD
  SIGNED="$OUT_DIR/GitHubble_0.26.0-beta-stage73-84_release.apk"
  "$BUILD_TOOLS/apksigner" sign \
    --ks "$GITHUBBLE_KEYSTORE" \
    --ks-key-alias "$ALIAS" \
    --ks-pass env:GITHUBBLE_STORE_PASSWORD \
    --key-pass env:GITHUBBLE_STORE_PASSWORD \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$SIGNED" "$ALIGNED"
  "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED"
fi

sha256sum "$OUT_DIR"/*.apk > "$OUT_DIR/SHA256SUMS.txt"
echo "Artifacts: $OUT_DIR"
