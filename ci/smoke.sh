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
# The head is held still (fixedHead): this emulator's rotation sensor jumps by over a radian
# while idle. Real sensor tracking is therefore not covered here.
# Pose the virtual phone upright in landscape.
adb emu sensor set acceleration 9.81:0:0
fail=0
wait_for() { # message, seconds
  for _ in $(seq 1 "$2"); do adb logcat -d -s NexusXR | grep -q "$1" && { echo "PASS: $1"; return 0; }; sleep 1; done
  echo "FAIL: $1"; fail=1; return 1
}
adb logcat -c
adb shell am start -W -n $PKG/.xr.XrActivity --ez fixedHead true
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
# The physical panel in landscape. The app's own virtual displays must not be picked up.
read -r A B < <(adb shell wm size | grep -m1 'Physical size' | grep -o '[0-9]*x[0-9]*' | sed 's/x/ /')
W=$((A > B ? A : B)); H=$((A > B ? B : A))
echo "Pressing on a ${W}x${H} panel"
press() { adb shell input swipe $((W / 2)) $((H / 2)) $((W / 2)) $((H / 2)) "$1"; }
# A 4 second hold is a long press and must not click.
press 4000
wait_for "long press on" 20
sleep 3
if adb logcat -d -s NexusXR | grep -q "open CALCULATOR"; then echo "FAIL: a long hold clicked"; fail=1; else echo "PASS: long hold did not click"; fi
# 1.2 seconds is past Android's own long-press time but under 2 seconds, so it is a tap.
press 1200
wait_for "open CALCULATOR" 20
sleep 8
adb exec-out screencap -p > smoke/spatial-after-tap.png
# A photo and a short recording must both be written to the media store.
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -W -n $PKG/.xr.XrActivity --ez fixedHead true --es open CAMERA --ez captureTest true
wait_for "Photo saved" 60
wait_for "Recording saved" 60
adb exec-out screencap -p > smoke/app-camera.png
adb logcat -d > smoke/logcat-capture.txt
# Sentences go through the same path as the microphone (recognition itself is not covered here):
# typing into a field, then reshaping the window, which must keep what was typed.
adb shell am force-stop $PKG
adb logcat -c
adb shell "am start -W -n $PKG/.xr.XrActivity --ez fixedHead true --es say 'open notes|type hello from voice control|wider|wider|shorter'"
wait_for "Command Type done" 60
wait_for "Command Do done" 60
sleep 25
adb exec-out screencap -p > smoke/voice-typing.png
if [ "$(adb logcat -d -s NexusXR | grep -c 'Command Do done')" -ge 3 ]; then echo "PASS: window reshaped three times"; else echo "FAIL: reshape commands"; fail=1; fi
if adb logcat -d -s NexusXR | grep -q "Resizing nexus-.* failed"; then echo "FAIL: reshaping failed"; fail=1; fi
adb logcat -d > smoke/logcat-voice.txt
# Screens of other windows, for review.
for apps in CENTER SETTINGS BROWSER PHOTOS FILES FIT KEYBOARD TASKS CALENDAR PAINT CONVERTER GAMES WEATHER VOICE; do
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.xr.XrActivity --ez fixedHead true --es open $apps
  sleep 22
  adb exec-out screencap -p > smoke/app-$(echo $apps | tr 'A-Z' 'a-z').png
done
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.xr.XrActivity --ez fixedHead true --ez headset true
sleep 25
adb exec-out screencap -p > smoke/headset.png
# Moved, resized pictures with a message and the microphone badge, which both eyes must see.
adb shell am force-stop $PKG
adb logcat -c
adb shell "am start -W -n $PKG/.xr.XrActivity --ez fixedHead true --ez headset true --es say 'smaller screens|smaller screens|wider gap|wider gap|what time is it'"
for _ in $(seq 1 90); do [ "$(adb logcat -d -s NexusXR | grep -c 'Command Do done')" -ge 5 ] && break; sleep 1; done
sleep 1
adb exec-out screencap -p > smoke/headset-fitted.png
if [ "$(adb logcat -d -s NexusXR | grep -c 'Command Do done')" -ge 5 ]; then echo "PASS: headset screens moved by voice"; else echo "FAIL: headset fit commands"; fail=1; fi
adb logcat -d > smoke/logcat-headset.txt
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity; sleep 8
adb exec-out screencap -p > smoke/start-screen.png
adb logcat -d > smoke/logcat-last.txt
cat smoke/logcat-capture.txt smoke/logcat-voice.txt smoke/logcat-headset.txt smoke/logcat-last.txt > smoke/logcat.txt
grep -E "NexusXR|FATAL EXCEPTION" smoke/logcat.txt > smoke/summary.txt
grep -q "FATAL EXCEPTION" smoke/logcat.txt && { echo "FAIL: crash detected"; fail=1; }
if grep -q "Immersive view halted" smoke/logcat.txt; then echo "NOTE: the camera safety stop appeared during this run"; fi
exit $fail
