#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?ANDROID_HOME is required}"
FILE="${1:?APK path required}"
BT="${ANDROID_HOME}/build-tools/36.0.0"
"$BT/zipalign" -c -v 4 "$FILE"
"$BT/apksigner" verify --verbose --print-certs "$FILE"
unzip -t "$FILE" >/dev/null
if unzip -l "$FILE" | grep -Eiq '\.(jks|keystore|pem|p12)$'; then echo 'Private key material found in APK'; exit 2; fi
sha256sum "$FILE"
