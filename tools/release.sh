#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${GITHUB_CLIENT_ID:?Public GitHub App Client ID is required}"
: "${GITHUBBLE_KEYSTORE:?Path to private release keystore is required}"
: "${GITHUBBLE_STORE_PASSWORD:?Keystore password is required}"
: "${GITHUBBLE_KEY_ALIAS:=githubble-release}"
GRADLE_BIN="${GRADLE_BIN:-$ROOT/gradlew}"
BUILD_TOOLS="${ANDROID_HOME}/build-tools/36.0.0"
source "$ROOT/version.properties"
OUT="${ROOT}/release/out"
rm -rf "$OUT" && mkdir -p "$OUT"
cd "$ROOT"
"$GRADLE_BIN" --no-daemon clean assembleRelease assembleDebugAndroidTest bundleRelease
"$ROOT/tools/generate-sbom.py" "$ROOT" "$OUT/GitHubble_${VERSION_NAME}_SPDX.json"
"$BUILD_TOOLS/zipalign" -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$OUT/GitHubble_${VERSION_NAME}-aligned.apk"
export GITHUBBLE_STORE_PASSWORD
"$BUILD_TOOLS/apksigner" sign --ks "$GITHUBBLE_KEYSTORE" --ks-key-alias "$GITHUBBLE_KEY_ALIAS" --ks-pass env:GITHUBBLE_STORE_PASSWORD --key-pass env:GITHUBBLE_STORE_PASSWORD --out "$OUT/GitHubble_${VERSION_NAME}.apk" "$OUT/GitHubble_${VERSION_NAME}-aligned.apk"
cp app/build/outputs/bundle/release/app-release.aab "$OUT/GitHubble_${VERSION_NAME}.aab"
jarsigner -keystore "$GITHUBBLE_KEYSTORE" -storepass:env GITHUBBLE_STORE_PASSWORD -keypass:env GITHUBBLE_STORE_PASSWORD "$OUT/GitHubble_${VERSION_NAME}.aab" "$GITHUBBLE_KEY_ALIAS"
if [[ -f app/build/outputs/mapping/release/mapping.txt ]]; then cp app/build/outputs/mapping/release/mapping.txt "$OUT/mapping.txt"; fi
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$OUT/GitHubble_${VERSION_NAME}-instrumentation.apk"
"$BUILD_TOOLS/zipalign" -c -v 4 "$OUT/GitHubble_${VERSION_NAME}.apk"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUT/GitHubble_${VERSION_NAME}.apk"
(cd "$OUT"; sha256sum ./*.apk ./*.aab ./*.json > SHA256SUMS.txt)
echo "Release output: $OUT"
