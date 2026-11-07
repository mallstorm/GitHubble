#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK path}"
cd "$ROOT"
exec ./gradlew --no-daemon test assembleDebug assembleRelease "$@"
