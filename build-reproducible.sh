#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK path}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
GRADLE_BIN="${GRADLE_BIN:-$ROOT/gradlew}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"

: "${GITHUB_CLIENT_ID:?Set GITHUB_CLIENT_ID before building}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" JAVA_HOME
export PATH="$JAVA_HOME/bin:$(dirname "$GRADLE_BIN"):$PATH"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1767225600}"

cd "$ROOT"
source "$ROOT/version.properties"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
"$GRADLE_BIN" --no-daemon clean assembleRelease assembleDebugAndroidTest

UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="$OUT_DIR/GitHubble_${VERSION_NAME}_aligned-unsigned.apk"
"$BUILD_TOOLS/zipalign" -p -f 4 "$UNSIGNED" "$ALIGNED"

if [[ -n "${GITHUBBLE_KEYSTORE:-}" ]]; then
  : "${GITHUBBLE_STORE_PASSWORD:?Set GITHUBBLE_STORE_PASSWORD for signing}"
  ALIAS="${GITHUBBLE_KEY_ALIAS:-githubble-release}"
  export GITHUBBLE_STORE_PASSWORD
  SIGNED="$OUT_DIR/GitHubble_${VERSION_NAME}_release.apk"
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

(cd "$OUT_DIR"; sha256sum ./*.apk > SHA256SUMS.txt)
echo "Artifacts: $OUT_DIR"
