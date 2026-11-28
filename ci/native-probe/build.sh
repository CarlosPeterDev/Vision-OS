#!/usr/bin/env bash
# Builds the test target APK and the probe's dex with the SDK's own tools.
set -euo pipefail
cd "$(dirname "$0")"
tools="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
jar="$ANDROID_HOME/platforms/$(ls "$ANDROID_HOME/platforms" | grep -E '^android-3[4-6]$' | sort -V | tail -1)/android.jar"
mkdir -p out/target-classes out/probe-classes out/target-dex out/probe
javac --release 11 -classpath "$jar" -d out/target-classes target/src/com/nexus/probetarget/*.java
"$tools/d8" --min-api 29 --lib "$jar" --output out/target-dex $(find out/target-classes -name '*.class')
"$tools/aapt2" link -I "$jar" --manifest target/AndroidManifest.xml -o out/target-unsigned.apk
(cd out/target-dex && zip -q ../target-unsigned.apk classes.dex)
"$tools/zipalign" -f 4 out/target-unsigned.apk out/target.apk
# A throwaway key, used only inside this test run.
keytool -genkeypair -keystore out/test.jks -storepass probe123 -keypass probe123 -alias probe \
  -keyalg RSA -keysize 2048 -validity 2 -dname CN=probe
"$tools/apksigner" sign --ks out/test.jks --ks-pass pass:probe123 out/target.apk
javac --release 11 -classpath "$jar" -d out/probe-classes src/probe/Probe.java
"$tools/d8" --min-api 31 --lib "$jar" --output out/probe $(find out/probe-classes -name '*.class')
(cd out/probe && zip -q ../probe.jar classes.dex)
