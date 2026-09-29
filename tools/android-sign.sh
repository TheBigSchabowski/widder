#!/bin/bash
# Creates the release key for the APK once. It lives outside the project on purpose:
# every future version must be signed with the same key, otherwise Android refuses to
# install an update over the old app (and uninstalling takes the learning progress with it).
#
#   ./tools/android-sign.sh
set -euo pipefail

KEYDIR="$HOME/.android"
KEYSTORE="$KEYDIR/widder-release.jks"
PASSFILE="$KEYDIR/widder-release.pass"
KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"

umask 077
mkdir -p "$KEYDIR"

if [ -f "$KEYSTORE" ]; then
  echo "Schluessel vorhanden: $KEYSTORE"
else
  openssl rand -hex 24 > "$PASSFILE"
  "$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" -alias widder \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$(cat "$PASSFILE")" -keypass "$(cat "$PASSFILE")" \
    -dname "CN=Widder, OU=Privat, O=Privat, C=DE" >/dev/null
  echo "Schluessel erzeugt: $KEYSTORE"
  echo "WICHTIG: $KEYSTORE und $PASSFILE sichern."
fi

"$KEYTOOL" -list -keystore "$KEYSTORE" -storepass "$(tr -d '\r\n' < "$PASSFILE")" >/dev/null \
  || { echo "Schluessel und Passwort in $PASSFILE passen nicht zusammen." >&2; exit 1; }
