#!/usr/bin/env bash
set -uo pipefail
mkdir -p device-results
status=0
gradle :app:connectedDebugAndroidTest --info --stacktrace 2>&1 | tee device-results/gradle.log || status=$?
# Preserve the real failing status. Collect diagnostics before the emulator stops.
adb logcat -d -v threadtime > device-results/logcat.txt 2>&1 || true
if adb exec-out run-as com.hoons1994.iphoneduoanimation tar -C files -cf - projection-frames > device-results/projection-frames.tar; then
  mkdir -p device-results/frames
  tar -xf device-results/projection-frames.tar -C device-results/frames || true
else
  rm -f device-results/projection-frames.tar
fi
find app/build/outputs/androidTest-results -name '*.xml' -exec cat {} \; 2>/dev/null || true
exit "$status"
