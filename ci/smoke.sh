#!/usr/bin/env bash
# Emulator smoke test. The arm64 libraries that ship to phones run through the emulator's
# ARM translation. It proves the pipeline runs; it says nothing about tracking accuracy,
# performance or behaviour on real hardware.
set -uo pipefail
PKG=com.nexus.visionos
mkdir -p smoke
adb install -r --abi arm64-v8a dist/*.apk || exit 1
adb root; sleep 3; adb wait-for-device
adb shell pm grant $PKG android.permission.CAMERA
adb shell settings put secure immersive_mode_confirmations confirmed
# A slow emulator makes the system launcher show "not responding" dialogs over the app.
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
# Pose the virtual phone upright in landscape.
adb emu sensor set acceleration 9.81:0:0
fail=0
wait_for() { # message, seconds
  for _ in $(seq 1 "$2"); do adb logcat -d -s NexusXR | grep -q "$1" && { echo "PASS: $1"; return 0; }; sleep 1; done
  echo "FAIL: $1"; fail=1; return 1
}
adb logcat -c
adb shell am start -W -n $PKG/.xr.XrActivity
wait_for "First XR frame rendered" 60
wait_for "Spatial surface nexus-1 ready" 30
wait_for "Windows placed" 30
wait_for "Hand landmarker ready" 30
wait_for "First camera frame" 60
wait_for "First hand tracking result" 90
sleep 8
adb exec-out screencap -p > smoke/spatial-home.png
# Touch goes through the same ray, window and virtual display path as a pinch.
# The middle of the upper icon row in Home is the Calculator.
read -r W H < <(adb shell dumpsys window displays | grep -o 'cur=[0-9]*x[0-9]*' | head -1 | sed 's/cur=//; s/x/ /')
adb shell input tap $((W / 2)) $((H * 47 / 100))
wait_for "Spatial surface nexus-2 ready" 20
sleep 6
adb exec-out screencap -p > smoke/spatial-after-tap.png
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.xr.XrActivity --ez headset true
sleep 25
adb exec-out screencap -p > smoke/headset.png
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity; sleep 8
adb exec-out screencap -p > smoke/start-screen.png
adb logcat -d > smoke/logcat.txt
grep -E "NexusXR|FATAL EXCEPTION" smoke/logcat.txt > smoke/summary.txt
grep -q "FATAL EXCEPTION" smoke/logcat.txt && { echo "FAIL: crash detected"; fail=1; }
exit $fail
