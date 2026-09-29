#!/bin/bash
# Runs the release APK on a headless Android emulator (no window, so it does not get in
# the way on the desktop) and saves a screenshot.
#
#   ./tools/android-emulator.sh                 install + start app, screenshot to build/emulator.png
#   ./tools/android-emulator.sh shot [name]     only take another screenshot
#   ./tools/android-emulator.sh stop            shut the emulator down
#
# One-time setup (done once on this Mac):
#   sdkmanager "system-images;android-36;google_apis;arm64-v8a"
set -euo pipefail
cd "$(dirname "$0")/.."

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
AVD="EroeffnungsPuzzle_Pixel"
IMAGE="system-images;android-36;google_apis;arm64-v8a"
APK="androidApp/build/outputs/apk/release/androidApp-release.apk"
PACKAGE="de.privat.widder"
mkdir -p build

shot() {
  local out="build/${1:-emulator}.png"
  "$ADB" exec-out screencap -p > "$out"
  echo "Screenshot: $out"
}

case "${1:-run}" in
  shot) shot "${2:-emulator}"; exit 0 ;;
  stop) "$ADB" emu kill 2>/dev/null || true; exit 0 ;;
esac

if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
  echo "==> Lege Emulator $AVD an (Pixel 6)..."
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_6 >/dev/null
fi

if ! "$ADB" devices | grep -q "^emulator-"; then
  echo "==> Starte Emulator ohne Fenster..."
  "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
    > build/emulator.log 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi

[ -s "$APK" ] || ./gradlew :androidApp:assembleRelease
echo "==> Installiere $APK..."
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -W -n "$PACKAGE/widder.android.MainActivity" >/dev/null
sleep 4
shot emulator
