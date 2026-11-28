#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
adb install -r out/target.apk || exit 1
adb push out/probe.jar /data/local/tmp/probe.jar || exit 1
adb shell settings put global hide_error_dialogs 1
sleep 20
adb logcat -c
# "adb shell" runs as uid 2000. adb root is deliberately never used here.
adb shell "CLASSPATH=/data/local/tmp/probe.jar timeout 120 app_process /system/bin probe.Probe" | tee out/result.txt
grep -q '^failures=0' out/result.txt
