#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/mnt/data/offline_env/APKForge-Offline-Dev-Linux-x86_64/android-sdk}"
JAVA_HOME="${JAVA_HOME:-/mnt/data/offline_env/APKForge-Offline-Dev-Linux-x86_64/tools/jdk-21}"
GRADLE_BIN="${GRADLE_BIN:-/mnt/data/offline_env/APKForge-Offline-Dev-Linux-x86_64/tools/gradle/gradle-9.5.1/bin/gradle}"
: "${GITHUB_CLIENT_ID:?Set GITHUB_CLIENT_ID before verification}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME" JAVA_HOME
export PATH="$JAVA_HOME/bin:$(dirname "$GRADLE_BIN"):$PATH"
cd "$ROOT"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
for run in 1 2; do
  "$GRADLE_BIN" --offline --no-daemon clean assembleRelease >/dev/null
  cp app/build/outputs/apk/release/app-release-unsigned.apk "$TMP/run-$run.apk"
done
sha256sum "$TMP"/*.apk
cmp "$TMP/run-1.apk" "$TMP/run-2.apk"
echo "PASS: two clean unsigned APK builds are byte-identical"
