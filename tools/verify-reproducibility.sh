#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK path}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
GRADLE_BIN="${GRADLE_BIN:-$ROOT/gradlew}"
: "${GITHUB_CLIENT_ID:?Set GITHUB_CLIENT_ID before verification}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" JAVA_HOME
export PATH="$JAVA_HOME/bin:$(dirname "$GRADLE_BIN"):$PATH"
cd "$ROOT"
source "$ROOT/version.properties"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
for run in 1 2; do
  "$GRADLE_BIN" --no-daemon clean assembleRelease >/dev/null
  cp app/build/outputs/apk/release/app-release-unsigned.apk "$TMP/run-$run.apk"
done
sha256sum "$TMP"/*.apk
cmp "$TMP/run-1.apk" "$TMP/run-2.apk"
echo "PASS: two clean unsigned APK builds are byte-identical"
