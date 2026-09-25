#!/usr/bin/env bash
set -uo pipefail
mkdir -p device-results/frames
status=0
gradle :app:connectedDebugAndroidTest --info --stacktrace 2>&1 | tee device-results/gradle.log || status=$?
# Preserve the real failing status; AGP copies additionalTestOutputDir before
# uninstalling the app. Never depend on run-as after that cleanup has happened.
adb logcat -d -v threadtime > device-results/logcat.txt 2>&1 || true
output=app/build/outputs/connected_android_test_additional_output
if [[ -d "$output" ]]; then
  find "$output" -type f -name '*.png' -exec cp {} device-results/frames/ \;
fi
find app/build/outputs/androidTest-results -name '*.xml' -exec cat {} \; 2>/dev/null || true
exit "$status"
