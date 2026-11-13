#!/usr/bin/env bash
set -euo pipefail

# Keep the emulator-runner entry point a single command; multiline shell control
# flow is evaluated here, independent of how the action splits its script input.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 2147483647
adb shell input keyevent 224
adb shell wm dismiss-keyguard
mkdir -p redesign-ui

if [ "${UI_SUITE:-redesign}" = 'agent-model-picker' ]; then
  timeout -k 10s 240s adb shell am instrument -w -e model_picker_only true \
    app.githubble.mobile.debug.test/app.githubble.mobile.AgentChatUiInstrumentation \
    > redesign-ui/model-picker-result.txt 2>&1 || true
  cat redesign-ui/model-picker-result.txt
  adb pull /sdcard/Android/data/app.githubble.mobile.debug/files/model-picker-ui/. redesign-ui/ || true
  adb logcat -d -s AndroidRuntime > redesign-ui/android-runtime.txt
  grep -q 'PASS: 12 model-picker' redesign-ui/model-picker-result.txt
  timeout -k 10s 240s adb shell am instrument -w \
    app.githubble.mobile.debug.test/app.githubble.mobile.AgentChatUiInstrumentation \
    > redesign-ui/agent-chat-result.txt 2>&1 || true
  cat redesign-ui/agent-chat-result.txt
  adb pull /sdcard/Android/data/app.githubble.mobile.debug/files/agent-ui/. redesign-ui/ || true
  grep -q 'PASS: agent chat UI' redesign-ui/agent-chat-result.txt
else
  timeout -k 10s 240s adb shell am instrument -w \
    app.githubble.mobile.debug.test/app.githubble.mobile.RedesignUiInstrumentation \
    > redesign-ui/redesign-result.txt 2>&1 || true
  cat redesign-ui/redesign-result.txt
  adb pull /sdcard/Android/data/app.githubble.mobile.debug/files/redesign-ui/. redesign-ui/ || true
  cat redesign-ui/audit.txt || true
  grep -q 'PASS: redesign UI' redesign-ui/redesign-result.txt
fi
