#!/usr/bin/env bash
# Build the debug app and (re)install it on the running emulator/phone.
#   bash scripts/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$LOCALAPPDATA/Android/jdk-21.0.13+9}"
export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
./gradlew assembleDebug -q --console=plain
ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
APK="app/build/outputs/apk/debug/app-$ABI-debug.apk"
[ -f "$APK" ] || APK="app/build/outputs/apk/debug/app-universal-debug.apk"
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell am force-stop com.rojea.ytdd
"$ADB" shell am start -n com.rojea.ytdd/.MainActivity >/dev/null
echo "YTDD installed ($APK) and launched."
