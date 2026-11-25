#!/usr/bin/env bash
# Local check without Maven access: uses the Kotlin compiler bundled in a Gradle install.
set -euo pipefail
cd "$(dirname "$0")"
L=${GRADLE_LIB:-/opt/gradle/lib}
j() { ls $L/$1 | head -1; }
LIBS="$(j 'kotlin-stdlib-2*.jar'):$(j 'kotlinx-coroutines-core-jvm-*.jar'):$(j 'kotlinx-serialization-core-jvm-*.jar'):$(j 'kotlinx-serialization-json-jvm-*.jar'):$(j 'junit-4*.jar'):$(j 'hamcrest-core-*.jar')"
OUT=$(mktemp -d)
java -cp "$(j 'kotlin-compiler-embeddable-*.jar'):$(j 'kotlin-stdlib-2*.jar'):$(j 'kotlin-script-runtime-*.jar'):$(j 'kotlin-reflect-*.jar'):$(j 'kotlin-daemon-embeddable-*.jar'):$(ls $L/trove4j*.jar $L/annotations*.jar 2>/dev/null | tr '\n' ':')$(j 'kotlinx-coroutines-core-jvm-*.jar')" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$LIBS" -d "$OUT" audio-engine/src ai-assistant/src
java -cp "$OUT:$LIBS" org.junit.runner.JUnitCore com.nexus.voice.VoiceCoreTest com.nexus.ai.SseTest
