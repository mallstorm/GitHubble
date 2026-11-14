#!/usr/bin/env bash
set -euo pipefail
CHECK_MODE=${1:?Choose rc, agent-ui, account-ui or r8-smoke}
case "$CHECK_MODE" in rc|agent-ui|account-ui|r8-smoke) ;; *) exit 2 ;; esac
case "$CHECK_MODE" in
  r8-smoke)
    adb install -r app/build/outputs/apk/release/app-release.apk
    adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
    timeout -k 10s 180s adb shell am instrument -w app.githubble.mobile.test/app.githubble.mobile.R8SmokeInstrumentation > stage2-device-result.txt || true
    grep -q 'PASS: minified Release UI + JNI + SOCKS' stage2-device-result.txt || { cat stage2-device-result.txt; adb logcat -d -s AndroidRuntime:E; exit 1; }
    ;;
  *)
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    case "$CHECK_MODE" in
      agent-ui) runner=AgentChatUiInstrumentation; expected='PASS: agent chat UI' ;;
      account-ui) runner=AccountAccessUiInstrumentation; expected='PASS: account access UI' ;;
      rc) runner=RcInstrumentationTest; expected='PASS: RC instrumentation suite' ;;
    esac
    timeout -k 10s 240s adb shell am instrument -w "app.githubble.mobile.debug.test/app.githubble.mobile.$runner" > stage2-device-result.txt || true
    grep -q "$expected" stage2-device-result.txt || { cat stage2-device-result.txt; adb logcat -d -s AndroidRuntime:E; exit 1; }
    ;;
esac
cat stage2-device-result.txt
mkdir -p stage2-screenshots
adb pull /sdcard/Android/data/app.githubble.mobile.debug/files/agent-ui/. stage2-screenshots/agent-ui/ || true
adb pull /sdcard/Android/data/app.githubble.mobile.debug/files/account-ui/. stage2-screenshots/account-ui/ || true
