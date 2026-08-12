#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK path}"
: "${GITHUB_CLIENT_ID:?Set public GitHub App Client ID}"
command -v gradle >/dev/null || { echo 'Gradle 9.5.1 is required'; exit 2; }
gradle --offline --no-daemon clean assembleRelease
