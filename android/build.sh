#!/usr/bin/env bash
# Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1794700800}"
PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${IGARECK_JAVA_HOME:?Set IGARECK_JAVA_HOME to JDK 17 or 21}"
: "${IGARECK_ANDROID_JAR:?Set IGARECK_ANDROID_JAR to Android 35 android.jar}"
: "${IGARECK_BUILD_TOOLS:?Set IGARECK_BUILD_TOOLS to Android Build Tools 35.0.0}"
if [[ "${IGARECK_UNSIGNED_ONLY:-0}" != 1 ]]; then
: "${IGARECK_KEYSTORE:?Set IGARECK_KEYSTORE to your private signing keystore}"
: "${IGARECK_KEY_ALIAS:?Set IGARECK_KEY_ALIAS}"
: "${IGARECK_STORE_PASS:?Set IGARECK_STORE_PASS}"
fi
export JAVA_HOME="$IGARECK_JAVA_HOME"
export PATH="$IGARECK_JAVA_HOME/bin:$PATH"
OUT="$PROJECT_ROOT/build"
mkdir -p "$OUT/obj" "$OUT/stage/lib" "$PROJECT_ROOT/dist"
# Rebuilding must not retain orphaned Java/generated classes from a prior version.
rm -rf -- "$OUT/classes" "$OUT/dex" "$OUT/generated"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/generated"
rm -rf -- "$OUT/stage/lib"
mkdir -p "$OUT/stage/lib"
python3 "$PROJECT_ROOT/tools/prepare_dependencies.py"
if [[ -n "${IGARECK_NATIVE_FROM_APK:-}" ]]; then
python3 "$PROJECT_ROOT/tools/reuse_native.py" --apk "$IGARECK_NATIVE_FROM_APK" --output "$OUT"
else
: "${IGARECK_NDK:?Set IGARECK_NDK to r28c or IGARECK_NATIVE_FROM_APK to a verified APK with matching native libraries}"
python3 "$PROJECT_ROOT/tools/build_nfqws2.py" --ndk "$IGARECK_NDK" --output "$OUT"
python3 "$PROJECT_ROOT/tools/build_spake2.py" --ndk "$IGARECK_NDK" --output "$OUT"
fi
PACKET_DEPS_CLASSPATH=$(find "$OUT/deps" -name '*.jar' -printf '%p:' | sed 's/:$//')
for INTERFACE in ITransportService; do
"$IGARECK_BUILD_TOOLS/aidl" -p"$(dirname "$IGARECK_ANDROID_JAR")/framework.aidl" \
    -I"$PROJECT_ROOT/app/src/main/aidl" -o"$OUT/generated" \
    "$PROJECT_ROOT/app/src/main/aidl/com/igareck/antidpi/$INTERFACE.aidl"
done
"$IGARECK_BUILD_TOOLS/aapt2" compile --dir "$PROJECT_ROOT/app/src/main/res" -o "$OUT/resources.zip"
"$IGARECK_BUILD_TOOLS/aapt2" link -o "$OUT/resources.apk" -I "$IGARECK_ANDROID_JAR" \
    --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" --java "$OUT/generated" \
    -A "$PROJECT_ROOT/app/src/main/assets" "$OUT/resources.zip"
find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/vendor/libadb-android/libadb/src/main/java" "$OUT/generated" -name '*.java' -print > "$OUT/java-sources.list"
"$IGARECK_JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$IGARECK_ANDROID_JAR:$IGARECK_BUILD_TOOLS/core-lambda-stubs.jar" \
    -classpath "$PACKET_DEPS_CLASSPATH" -g:none -Xlint:deprecation -d "$OUT/classes" @"$OUT/java-sources.list"
"$IGARECK_JAVA_HOME/bin/jar" --create --file "$OUT/classes.jar" -C "$OUT/classes" .
DEPS=("$OUT"/deps/*.jar)
"$IGARECK_BUILD_TOOLS/d8" --release --min-api 30 --lib "$IGARECK_ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar" "${DEPS[@]}"
python3 "$PROJECT_ROOT/tools/assemble_apk.py" "$OUT"
"$IGARECK_BUILD_TOOLS/zipalign" -f -P 16 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
APK="$PROJECT_ROOT/dist/Igareck_AntiDPI_v1.0_Android_11+_Universal.apk"
if [[ "${IGARECK_UNSIGNED_ONLY:-0}" == 1 ]]; then
APK="${APK%.apk}.unsigned.apk"
cp -- "$OUT/aligned.apk" "$APK"
"$IGARECK_BUILD_TOOLS/zipalign" -c -P 16 4 "$APK"
python3 "$PROJECT_ROOT/tools/check_apk_packaging.py" "$APK"
echo "Unsigned APK created (not installable until signed): $APK"
exit 0
fi
"$IGARECK_BUILD_TOOLS/apksigner" sign --ks "$IGARECK_KEYSTORE" --ks-key-alias "$IGARECK_KEY_ALIAS" \
    --ks-pass env:IGARECK_STORE_PASS --key-pass env:IGARECK_STORE_PASS --v1-signing-enabled true \
    --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "$APK" "$OUT/aligned.apk"
"$IGARECK_BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK"
"$IGARECK_BUILD_TOOLS/apksigner" verify --verbose --min-sdk-version 23 "$APK"
"$IGARECK_BUILD_TOOLS/zipalign" -c -P 16 4 "$APK"
python3 "$PROJECT_ROOT/tools/check_apk_packaging.py" "$APK"
echo "APK created: $APK"
