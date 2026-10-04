# Widder

Spaced-Repetition-Lernapp für Musik: Anki-artige Karteikarten, ein kleiner Audio-Editor und
Akkord-Gehörtraining per Drag & Drop. Eine Codebasis (Kotlin Multiplatform + Compose
Multiplatform) für **Android**, **macOS** und **Linux**.

## Die zwei Wege

**Karteikarten** – Stapel wie in Anki: Neu / Lernen / Fällig, Lernschritte 1 Min → 10 Min,
danach Tagesintervalle aus FSRS (Nochmal / Schwer / Gut / Leicht mit Intervall-Vorschau),
Tageslimits pro Stapel, Rückgängig, Aussetzen, Suche. Jede Seite einer Karte kann Audio haben:
aufnehmen, Datei wählen, MIDI abspielen.

**Akkord-Training** – Dateien hinzufügen, der Dateiname ist die Lösung:

| Datei | Lösung |
|---|---|
| `A D G F.wav` | A · D · G · F |
| `Fmin Gmin.mid` | Fmin · Gmin (gilt als gleich wie Fm Gm) |
| `Am_F_C_G (2).mp3` | Am · F · C · G – Klammern und Zahlen werden ignoriert |
| `ADGF.wav`, `FminGmin.wav` | ohne Trennzeichen wird an den Notenbuchstaben getrennt |
| `Mein Stück.mid` | kein Akkord im Namen → Widder erkennt die Akkorde aus den MIDI-Noten |

Beim Import zeigt Widder, wie er die Namen gelesen hat, und man kann korrigieren. Beim Üben
wird das Audio abgespielt, die Akkorde liegen gemischt im Vorrat und müssen in die Slots
gezogen werden (Maus oder Finger; Tippen geht auch). Optional mischt Widder ähnliche falsche
Akkorde dazu (Stapel-Optionen). Die Bewertung wird vorgeschlagen und fließt in denselben
FSRS-Scheduler.

**Startkit: A-Akkorde** (bei neuen Sammlungen dabei, sonst unter Akkord-Training → „Hinzufügen“):
Am(add9), Amaj(add9), A7, Amaj7, Am/C, Am/E, Amaj, A/C#, A/E, A-Oktave, A5 – jeder einzeln zum
Erkennen, dazu ein paar Folgen daraus (u. a. Amaj → A/C# → A/E). Die falschen Kacheln kommen aus
dem Kit selbst. Amaj klingt in Grundstellung ohne Extra-Bass, die Umkehrungen mit tiefem Basston.

Verstanden werden u. a. `m min - moll`, `7 maj7 M7 Δ`, `m7 m7b5 ø dim dim7 aug sus2 sus4 6 9 add9`,
Slash-Akkorde (`C/E`) und deutsche Namen (`H`, `Fis`, `Es`, `As`, `Cism`, `Des-Dur`).
In den Einstellungen: „Deutsche Notennamen“ (B = Bb).

## Ordner: Dateien von außen prüfen und tauschen

Jeder Stapel lässt sich mit einem Ordner verbinden (Knopf „Ordner“ am Stapel, Details in den
Stapel-Optionen). Der Ordner ist dann die Quelle:

| im Ordner | in Widder |
|---|---|
| neue Datei `Dm G C.mid` | neue Karte, Lösung aus dem Namen |
| Datei durch andere mit gleichem Namen ersetzt (auch `A7.mid` → `A7.wav`) | Audio der Karte ersetzt, Lernstand bleibt |
| Datei umbenannt | alte Karte weg, neue Karte mit neuer Lösung |
| Datei gelöscht | Karte entfernt |

Eingelesen wird beim Start, beim Öffnen des Stapels und wenn man zu Widder zurückkehrt – oder
per „Neu einlesen“. `/` ist in Dateinamen verboten; `Am∕C.mid` (mit dem Divisionsstrich ∕)
wird als `Am/C` gelesen.

**Startkit:** Auf dem Mac liegen die Dateien automatisch in
`~/Documents/Widder/Startkit A-Akkorde`, unter Linux im Dokumente-Ordner des Benutzers
(z. B. `~/Dokumente/Widder/Startkit A-Akkorde`). Auf Android „Ordner“ tippen und einen Ordner wählen
(am besten im Dialog einen neuen anlegen, z. B. `Documents/Widder Startkit`) – Android lässt
Apps nur in Ordner schreiben, die man selbst ausgesucht hat.

## Akkorde auf Karteikarten und Stufen

Auch eine Karteikarte kann Akkorde haben (Feld „Akkorde“ im Editor). Sie erscheinen auf der
Rückseite, und „Als Audio erzeugen“ macht aus ihnen gleich den Klang der Vorderseite. Das
Startkit gibt es deshalb auch als Karteikarten-Stapel.

Akkorde werden mit ihrer **Stufe** gezeigt: `I (A)`, `IV (D)`, `bVII (G)`, `vi (F#m)`,
Umkehrungen als `I/3` (Terz im Bass) und `I/5` (Quinte im Bass). Die Tonart erkennt Widder aus den
Akkorden einer Karte; nur welche Akkorde vorkommen zählt, nicht die Reihenfolge – sonst würde im
Training die „I“-Kachel verraten, was zuerst kommt. Festlegen lässt sie sich pro Stapel
(Stapel-Optionen) oder Karte. In den Einstellungen: Stufen aus / „I (A)“ / nur „I“.

Für alles ohne Akkorde oder feste Tonart gibt es das Feld **„Eigener Text statt Akkorden und
Stufen“** (z. B. `Son Clave 3-2`): Ist es ausgefüllt, zeigt die Karte diesen Text statt der
Akkord-Chips; im Training erscheint er nach dem Prüfen, die Stufen fallen weg.

Jedes Audio lässt sich per **„Herunterladen“** speichern – als WAV so wie es klingt (mit Schnitt
und Effekten, MIDI wird dabei zu Audio) oder als Originaldatei.

## Audio-Editor („Schneiden & Effekte“)

Nicht-destruktiv – die Originaldatei bleibt unverändert, alles ist später wieder änderbar,
auch mitten beim Lernen („Audio bearbeiten“ im Training, „Schneiden & Effekte“ bei der Abfrage):

- Anfang/Ende an der Wellenform schneiden (Marken ziehen oder tippen)
- Pegel ±24 dB, Normalisieren (auf −1 dBFS), Übersteuerung wird rot angezeigt
- Ein-/Ausblenden, Hall (Freeverb: Anteil, Raumgröße, Dämpfung), Tiefen-/Höhenfilter
- Anhören mit und ohne Effekte

MIDI-Dateien werden von einem eingebauten Synthesizer gerendert – auf Android, Mac und Linux
gleich, und durch dieselben Effekte.

## Spielerisches

XP und Level, Tage-in-Folge, Serien-Bonus im Training und die **Bildbelohnung** aus dem
AnkiDroid-Fork: Ein eigenes Bild liegt unscharf hinter dem Startbildschirm und wird pro
Lernzeit-Abschnitt Kachel für Kachel scharf. Anders als im Fork liegen die Kacheln über dem
sichtbaren Bildschirm, deshalb braucht es keinen Zuschnitt-Schritt mehr. Es zählt nur Zeit beim
Abfragen/Trainieren mit Fenster im Vordergrund.

## Starten

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # Linux: ein JDK 21

./gradlew :desktopApp:run                          # Desktop-App (Mac, Linux)
./gradlew :desktopApp:run -PdataDir=/tmp/widder    # mit Test-Sammlung
./gradlew :desktopApp:packageDmg                   # Mac: DMG in desktopApp/build/compose/binaries
./gradlew :desktopApp:packageDeb                   # Linux: DEB in desktopApp/build/compose/binaries

./gradlew :androidApp:assembleRelease              # APK in androidApp/build/outputs/apk/release
./tools/android-emulator.sh                        # APK im Emulator ohne Fenster + Screenshot

./gradlew :shared:desktopTest                      # Tests
./gradlew :desktopApp:renderScreens                # alle Bildschirme als PNG (Handy + Desktop)
```

Release bauen: `./tools/build-release.sh` legt DMG, APK und Prüfsummen in `dist/releases`.
Die APK wird mit `~/.android/widder-release.jks` signiert (einmalig erzeugt von
`tools/android-sign.sh`). **Schlüssel und `.pass`-Datei sichern:** Nur damit signierte Versionen
lassen sich über die installierte App aktualisieren. Das Icon kommt aus `tools/make-icon.sh`
(`desktopApp/icon.png`, das Fenster-Icon unter Linux, ist daraus abgeleitet).

Auf dem Computer (Mac, Linux): Dateien (auch ganze Ordner) einfach ins Fenster ziehen. Tastatur
beim Abfragen: Leertaste, 1–4, R (nochmal hören), E (bearbeiten), ⌘Z bzw. Strg+Z; im Training
Enter = prüfen/weiter, ⌫ = letzten Akkord zurücklegen; Esc = zurück.
Auf Android: „Importieren“, oder aus einer anderen App „Teilen → Widder“ / „Öffnen mit“.

## Linux

Gebaut wird unter Linux selbst (jpackage erzeugt immer Pakete für das System, auf dem es läuft):
`./tools/build-linux.sh` führt die Desktop-Tests aus und legt ein `.deb` und ein portables
`.tar.gz` samt Prüfsummen in `dist/linux`. Dafür braucht es ein JDK 21 sowie `dpkg-deb` und
`fakeroot` (Debian/Ubuntu: `sudo apt install fakeroot`).

```bash
sudo apt install ./Widder_<Version>_linux-amd64.deb     # Programm „Widder“ im Anwendungsmenü
tar xzf Widder_<Version>_linux-amd64.tar.gz && Widder/bin/Widder    # ohne Installation
```

Das `.deb` ist unter Ubuntu 24.04 gebaut und passt auf Ubuntu 24.04+ und Debian 13+ (es hängt an
`libasound2t64`); auf älteren oder anderen Distributionen (Fedora, Arch, …) das `.tar.gz` nehmen,
es bringt seine Java-Laufzeit mit und braucht nur die üblichen X11-, ALSA- und Schrift-Bibliotheken.

- **Ton** läuft über javax.sound und damit über ALSA; PulseAudio und PipeWire sind über deren
  ALSA-Brücke (`pipewire-alsa`, `pulseaudio` – bei Ubuntu/Fedora dabei) angebunden.
- **Ordner wählen** („Ordner“ am Stapel): `zenity` (GNOME) oder `kdialog` (KDE), wenn installiert,
  sonst ein schlichter Java-Dialog. „Ordner öffnen“ geht über `xdg-open`.
- **Audioformate:** WAV/AIFF/AU direkt; MP3, M4A/AAC, FLAC und OGG Vorbis mit eingebauten
  Java-Decodern. Opus, CAF und ALAC lassen sich nur importieren, wenn `ffmpeg` installiert ist
  (wie auf dem Mac, wo `afconvert` die meisten Formate abdeckt). Die Decoder sind JLayer (MP3),
  JOrbis (OGG), jFLAC (FLAC; alle drei LGPL) und jaad (AAC; Apache 2.0), als unveränderte Jars im
  Programm.
- **Daten** liegen im Dokumente-Ordner des Benutzers, den `~/.config/user-dirs.dirs` festlegt
  (siehe „Daten“); Dateidialoge starten in Musik bzw. Downloads nach derselben Konfiguration.

## Daten

Mac: `~/Documents/Widder`, Linux: `Widder` im Dokumente-Ordner (nach XDG, z. B.
`~/Dokumente/Widder`, sonst `~/Documents/Widder`), Android: App-Speicher.
`collection.json` (Stapel, Karten, Einstellungen), `progress.json` (Lernstand, XP, Bildbelohnung),
`media/` (Audio, MIDI, Bild).

## Aufbau

```
shared/src/commonMain/kotlin/widder/
  core/    Modell, FSRS mit Lernschritten, Warteschlange, Akkord-Runde, Bildbelohnung, Speicher
  music/   Akkord-Parser/-Erkennung, Dateinamen-Parser, MIDI lesen/schreiben/analysieren
  audio/   PCM, WAV, Synth, Effekte (Filter, Freeverb), Player/Recorder-Schnittstellen
  ui/      Compose-Oberfläche und AppViewModel
shared/src/jvmCommonMain   Dateien, Zeit, Audio-Thread (für beide Plattformen)
shared/src/androidMain     AudioTrack, AudioRecord, MediaCodec (MP3/M4A/FLAC/OGG)
shared/src/desktopMain     javax.sound; Dateiformate: afconvert (Mac), Java-Decoder (MP3, M4A/AAC,
                           FLAC, OGG), ffmpeg falls vorhanden; Ordner-Dialoge, XDG-Verzeichnisse
androidApp/, desktopApp/   Einstiegspunkte
```

## Noch offen

- Import von Anki-Stapeln (`.apkg`), Lückentext, formatierter Text/Bilder auf Karten
- Sync zwischen Handy und Mac (bis dahin: Datenordner kopieren)
- Statistik-Ansicht
- Bei sehr großen Sammlungen (zehntausende Karten) auf SQLite umsteigen
