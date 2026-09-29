#!/bin/bash
# Renders the app icon and builds desktopApp/icon.icns from it (macOS tools only).
#
#   ./tools/make-icon.sh
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew -q :desktopApp:renderIcon
SRC="desktopApp/build/icon-1024.png"
SET="desktopApp/build/Widder.iconset"
rm -rf "$SET" && mkdir -p "$SET"
for size in 16 32 128 256 512; do
  sips -z $size $size "$SRC" --out "$SET/icon_${size}x${size}.png" >/dev/null
  sips -z $((size * 2)) $((size * 2)) "$SRC" --out "$SET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$SET" -o desktopApp/icon.icns
echo "Icon: desktopApp/icon.icns"
