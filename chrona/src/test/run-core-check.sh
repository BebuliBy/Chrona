#!/usr/bin/env bash
# Lightweight cached compiler/JUnit probe, NOT an Android Gradle build or runtime DB test.
# Run from repository root after a Gradle unit/build has generated Room and compiled BuildConfig.
set -euo pipefail
shopt -s nullglob
test -d chrona/build/generated/ksp/debug/kotlin/by/bebuliby/chrona/data
BUILD_CONFIG_CLASSES=chrona/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
test -f "$BUILD_CONFIG_CLASSES/by/bebuliby/chrona/BuildConfig.class"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
MODULES="$CACHE/modules-2/files-2.1"
JAVA="${JAVA_HOME:-$HOME/.local/opt/android-studio/jbr}/bin/java"
COMPILER=("$MODULES/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10/"*/*.jar)
STDLIB=("$MODULES/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/"*/*.jar)
COROUTINES=("$MODULES/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/"*/*.jar)
ANNOTATIONS=("$MODULES/org.jetbrains/annotations/23.0.0/"*/*.jar)
JUNIT=("$MODULES/junit/junit/4.13.2/"*/*.jar)
HAMCREST=("$MODULES/org.hamcrest/hamcrest-core/1.3/"*/*.jar)
JSON=("$MODULES/org.json/json/20240303/"*/*.jar)
COROUTINES_TEST=("$MODULES/org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm/1.10.2/"*/*.jar)
test "${#COMPILER[@]}" = 1 && test "${#STDLIB[@]}" = 1 && test "${#COROUTINES[@]}" = 1
test "${#ANNOTATIONS[@]}" = 1 && test "${#JUNIT[@]}" = 1 && test "${#HAMCREST[@]}" = 1
test "${#JSON[@]}" = 1 && test "${#COROUTINES_TEST[@]}" = 1
CP="${STDLIB[0]}:${COROUTINES[0]}:${ANNOTATIONS[0]}:${JUNIT[0]}:${HAMCREST[0]}:${JSON[0]}:${COROUTINES_TEST[0]}:${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-36/android.jar"
CP="$CP:$BUILD_CONFIG_CLASSES"
for JAR in "$CACHE/9.3.1/transforms/"*/transformed/*-api.jar \
    "$MODULES/androidx.room/room-common-jvm/2.8.5/"*/*.jar \
    "$MODULES/androidx.lifecycle/lifecycle-common-jvm/2.9.2/"*/*.jar \
    "$MODULES/androidx.datastore/datastore-core-okio-jvm/1.2.1/"*/*.jar; do
    CP="$CP:$JAR"
done
HOST_CP="${COMPILER[0]}:${STDLIB[0]}:${COROUTINES[0]}:${ANNOTATIONS[0]}"
"$JAVA" -cp "$HOST_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d chrona/build/core-android-check.jar \
    chrona/src/main/java/by/bebuliby/chrona/domain/*.kt \
    chrona/src/main/java/by/bebuliby/chrona/data/*.kt \
    chrona/src/main/java/by/bebuliby/chrona/data/client/*.kt \
    chrona/src/main/java/by/bebuliby/chrona/tracking/*.kt \
    chrona/src/main/java/by/bebuliby/chrona/presentation/*.kt \
    chrona/build/generated/ksp/debug/kotlin/by/bebuliby/chrona/data/*.kt
"$JAVA" -cp "$HOST_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -Xfriend-paths=chrona/build/core-android-check.jar -classpath "chrona/build/core-android-check.jar:$CP" \
    -d chrona/build/core-unit.jar \
    chrona/src/test/java/by/bebuliby/chrona/domain/*.kt \
    chrona/src/test/java/by/bebuliby/chrona/data/*.kt \
    chrona/src/test/java/by/bebuliby/chrona/tracking/*.kt \
    chrona/src/test/java/by/bebuliby/chrona/presentation/*.kt
"$JAVA" -cp "chrona/build/core-unit.jar:chrona/build/core-android-check.jar:$CP" org.junit.runner.JUnitCore \
    by.bebuliby.chrona.domain.ScoringTest \
    by.bebuliby.chrona.tracking.TrackingEngineTest \
    by.bebuliby.chrona.presentation.LocalReadModelsTest \
    by.bebuliby.chrona.data.RawConvertersTest \
    by.bebuliby.chrona.data.ClientProtocolTest \
    by.bebuliby.chrona.data.ClientFixesTest \
    by.bebuliby.chrona.data.UxCoreTest
