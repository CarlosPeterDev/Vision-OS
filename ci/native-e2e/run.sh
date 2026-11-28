#!/usr/bin/env bash
# Real Shizuku, real virtual display, real Nexus window, on an emulator. It uses a test app of our
# own. It does not show behaviour on a physical phone or with other companies' apps.
set -uo pipefail
PKG=com.nexus.visionos
SHIZUKU=moe.shizuku.privileged.api
TARGET=com.nexus.probetarget
here=ci/native-e2e
mkdir -p e2e
fail=0
pass() { echo "PASS: $1" | tee -a e2e/result.txt; }
bad() { echo "FAIL: $1" | tee -a e2e/result.txt; fail=1; }
check() { local name="$1"; shift; if "$@"; then pass "$name"; else bad "$name"; fi; }
log() { adb logcat -d -s NexusNative NexusXR NexusProbe; }
wait_log() { for _ in $(seq 1 "$2"); do log | grep -q "$1" && return 0; sleep 1; done; return 1; }
finish() {
  adb logcat -d > e2e/logcat.txt
  log > e2e/summary.txt
  cat e2e/summary.txt | tail -80
  python3 $here/show.py e2e/*.png
  echo "=== display and buffer messages"
  cat e2e/display.txt 2>/dev/null
  grep -iE "bufferqueue|surfacetexture|updateTexImage|nexus-native|nexus-[0-9]|VirtualDisplay" e2e/logcat-first.txt | tail -60
  echo "=== results"; cat e2e/result.txt
  exit $fail
}

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r ci/native-probe/out/target.apk || exit 1
adb install -r e2e-input/shizuku.apk || exit 1
adb shell pm grant $PKG android.permission.CAMERA
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global hide_error_dialogs 1
adb emu sensor set acceleration 9.81:0:0
sleep 20

# Start Shizuku exactly as its ADB instructions do: as the shell user, never as root.
adb shell monkey -p $SHIZUKU -c android.intent.category.LAUNCHER 1 > /dev/null
sleep 8
dir=$(adb shell pm path $SHIZUKU | head -1 | tr -d '\r' | sed 's/^package://; s#/base.apk$##')
adb shell "$dir/lib/x86_64/libshizuku.so" | tee e2e/shizuku-start.txt
sleep 6
check "Shizuku server is running as shell" bash -c "adb shell ps -A -o USER,NAME | grep -E '^shell +shizuku_server'"
adb shell input keyevent KEYCODE_HOME

adb logcat -c
adb shell am start -W -n $PKG/.MainActivity > /dev/null
sleep 5
check "open the setup screen from the main screen" python3 $here/ui.py tap "Android apps setup"
check "setup shows the Connect step" wait_log "setup state CONNECT" 30
adb exec-out screencap -p > e2e/setup-connect.png
check "press Connect" python3 $here/ui.py tap "Connect"
sleep 4
adb exec-out screencap -p > e2e/shizuku-dialog.png
check "allow in Shizuku's own dialog" python3 $here/ui.py tap "Allow all the time"
check "setup reaches Ready" wait_log "setup state READY" 30
sleep 4
adb exec-out screencap -p > e2e/setup-ready.png

# Everything runs as the ordinary shell user, through the same buttons a person would press.
read -r A B < <(adb shell wm size | grep -m1 'Physical size' | grep -o '[0-9]*x[0-9]*' | sed 's/x/ /')
W=$((A > B ? A : B)); H=$((A > B ? B : A))
open_in_nexus() { python3 $here/ui.py tap "Open Probe target in Nexus" 60; }

adb logcat -c
check "open the test app from the setup screen's list" open_in_nexus
check "Nexus's own service connected" wait_log "service connected" 90
check "private display created" wait_log "display [0-9]* created" 30
check "test app opened on it" wait_log "native app open on display" 40
check "test app reports that display" wait_log "target created on display [1-9]" 30
sleep 25
adb exec-out screencap -p > e2e/native-window-red.png
adb shell "dumpsys display | grep -i -A3 nexus-native; dumpsys SurfaceFlinger --display-id; dumpsys activity activities | grep -iE 'Display #|probetarget'" > e2e/display.txt 2>&1
check "the app's red screen is visible inside the Nexus window" python3 $here/colour.py e2e/native-window-red.png red
# A screen touch takes the same ray, window and injection path as a pinch.
adb shell input swipe $((W / 2)) $((H / 2)) $((W / 2)) $((H / 2)) 600
check "a tap on the window reaches the app" wait_log "target tapped" 30
sleep 12
adb exec-out screencap -p > e2e/native-window-green.png
check "the window now shows the app's green screen" python3 $here/colour.py e2e/native-window-green.png green
adb logcat -d > e2e/logcat-first.txt

# Closing Nexus must remove its display.
adb shell am force-stop $PKG
sleep 8
check "no Nexus display is left after Nexus stops" bash -c "! adb shell dumpsys display | grep -q '\"nexus-native\"'"

# Back, from a physical keyboard's Escape key, takes the same path as the window's Back button.
reopen() {
  adb shell am force-stop $PKG
  adb shell am force-stop $TARGET
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity > /dev/null
  sleep 4
  python3 $here/ui.py tap "Android apps setup" && sleep 3 && open_in_nexus
}
check "open the app again" reopen
check "it starts on a new private display" wait_log "target created on display [1-9]" 90
sleep 20
adb shell input keyevent KEYCODE_ESCAPE
check "Back reaches the app" wait_log "target got back" 40

# Stopping Shizuku must close the window with a message and leave Nexus running.
check "open the app a third time" reopen
if wait_log "native app open on display" 90; then
  sleep 8
  adb shell "pkill -f shizuku_server"; adb shell "pkill -f $PKG:nativedisplay"
  check "losing the service is noticed" wait_log "service disconnected" 30
  sleep 6
  adb exec-out screencap -p > e2e/native-lost.png
  check "Nexus keeps running without Shizuku" bash -c "adb shell pidof $PKG > /dev/null"
else bad "reopen before stopping Shizuku"; fi
check "no crash" bash -c "! adb logcat -d | grep -A3 'FATAL EXCEPTION' | grep -q 'com.nexus'"
finish
