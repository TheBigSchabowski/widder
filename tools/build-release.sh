#!/bin/bash
# Builds the macOS DMG and the signed Android APK and puts both, renamed for a
# GitHub release, into dist/releases together with SHA256 checksums.
#
#   ./tools/build-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(grep '^app.version=' gradle.properties | cut -d= -f2)"
RELEASE_DIR="dist/releases"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

# A DMG build that is interrupted leaves its work image attached, and the next build
# then fails with a meaningless jpackage error. Detach only images from this project.
echo "==> Loese haengengebliebene DMG-Abbilder..."
hdiutil info | awk -v dir="$PWD/" '
    index($0, "image-path") == 1 && index($0, dir) { found = 1 }
    /^\/dev\/disk/ && found { print $1; found = 0 }' |
  while read -r dev; do
    echo "    $dev"
    hdiutil detach "$dev" -force >/dev/null 2>&1 || true
  done

./tools/android-sign.sh

echo "==> Tests, DMG und APK..."
./gradlew :shared:desktopTest :desktopApp:packageDmg :androidApp:assembleRelease

DMG="desktopApp/build/compose/binaries/main/dmg/Widder-$VERSION.dmg"
APK="androidApp/build/outputs/apk/release/androidApp-release.apk"
for artifact in "$DMG" "$APK"; do
  [ -s "$artifact" ] || { echo "Build-Artefakt fehlt: $artifact" >&2; exit 1; }
done

mkdir -p "$RELEASE_DIR"
rm -f "$RELEASE_DIR"/*
cp "$DMG" "$RELEASE_DIR/Widder_${VERSION}_macos-arm64.dmg"
cp "$APK" "$RELEASE_DIR/Widder_${VERSION}_android.apk"
(cd "$RELEASE_DIR" && shasum -a 256 *.dmg *.apk > SHA256SUMS.txt)
echo "==> Fertig:"
ls -la "$RELEASE_DIR"
