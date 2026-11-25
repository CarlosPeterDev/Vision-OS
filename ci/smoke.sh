#!/usr/bin/env bash
# Emulator smoke test: the XR activity starts, creates a spatial window and renders.
# It says nothing about hand tracking accuracy or behaviour on real hardware.
set -uo pipefail
PKG=com.nexus.visionos
mkdir -p smoke
adb install -r dist/*.apk || exit 1
adb root; sleep 3; adb wait-for-device
adb shell pm grant $PKG android.permission.CAMERA
adb shell settings put secure immersive_mode_confirmations confirmed
# Pose the virtual phone upright in landscape, so windows are level.
adb emu sensor set acceleration 9.81:0:0
adb logcat -c
adb shell am start -W -n $PKG/.xr.XrActivity
sleep 30
adb exec-out screencap -p > smoke/spatial-home.png
# Touch fallback through the real pointer pipeline: tap inside the Home window.
read -r W H < <(adb shell dumpsys window displays | grep -o 'cur=[0-9]*x[0-9]*' | head -1 | sed 's/cur=//; s/x/ /')
adb shell input tap $((W / 2)) $((H / 2)); sleep 6
adb exec-out screencap -p > smoke/spatial-after-tap.png
adb shell am start -W -n $PKG/.MainActivity; sleep 5
adb exec-out screencap -p > smoke/start-screen.png
adb logcat -d > smoke/logcat.txt
grep -E "NexusXR|FATAL EXCEPTION|AndroidRuntime" smoke/logcat.txt | tee smoke/summary.txt
fail=0
grep -q "FATAL EXCEPTION" smoke/logcat.txt && { echo "Crash detected"; fail=1; }
grep -q "First XR frame rendered" smoke/summary.txt || { echo "No XR frame"; fail=1; }
grep -q "Spatial surface nexus-1 ready" smoke/summary.txt || { echo "No spatial window"; fail=1; }
exit $fail
