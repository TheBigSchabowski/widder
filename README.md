# Widder

Spaced-Repetition-Lernapp für Musik: Anki-artige Karteikarten, ein kleiner Audio-Editor und
Akkord-Gehörtraining per Drag & Drop. Eine Codebasis (Kotlin Multiplatform + Compose
Multiplatform) für **Android** und **macOS**.

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
Am(add9), Amaj(add9), A7, Amaj7, Am/C, Am/E, Amaj, A-Oktave, A5, A/C#, A/A – jeder einzeln zum
Erkennen, dazu ein paar Folgen daraus. Die falschen Kacheln kommen aus dem Kit selbst. Akkorde
ohne Slash klingen ohne Extra-Bass, Slash-Akkorde mit tiefem Basston – so unterscheiden sich
Amaj und A/A hörbar.

Verstanden werden u. a. `m min - moll`, `7 maj7 M7 Δ`, `m7 m7b5 ø dim dim7 aug sus2 sus4 6 9 add9`,
Slash-Akkorde (`C/E`) und deutsche Namen (`H`, `Fis`, `Es`, `As`, `Cism`, `Des-Dur`).
In den Einstellungen: „Deutsche Notennamen“ (B = Bb).

## Audio-Editor („Schneiden & Effekte“)

Nicht-destruktiv – die Originaldatei bleibt unverändert, alles ist später wieder änderbar,
auch mitten beim Lernen („Audio bearbeiten“ im Training, „Schneiden & Effekte“ bei der Abfrage):

- Anfang/Ende an der Wellenform schneiden (Marken ziehen oder tippen)
- Pegel ±24 dB, Normalisieren (auf −1 dBFS), Übersteuerung wird rot angezeigt
- Ein-/Ausblenden, Hall (Freeverb: Anteil, Raumgröße, Dämpfung), Tiefen-/Höhenfilter
- Anhören mit und ohne Effekte

MIDI-Dateien werden von einem eingebauten Synthesizer gerendert – auf Android und Mac gleich,
und durch dieselben Effekte.

## Spielerisches

XP und Level, Tage-in-Folge, Serien-Bonus im Training und die **Bildbelohnung** aus dem
AnkiDroid-Fork: Ein eigenes Bild liegt unscharf hinter dem Startbildschirm und wird pro
Lernzeit-Abschnitt Kachel für Kachel scharf. Anders als im Fork liegen die Kacheln über dem
sichtbaren Bildschirm, deshalb braucht es keinen Zuschnitt-Schritt mehr. Es zählt nur Zeit beim
Abfragen/Trainieren mit Fenster im Vordergrund.

## Starten

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :desktopApp:run                          # Mac-App
./gradlew :desktopApp:run -PdataDir=/tmp/widder    # mit Test-Sammlung
./gradlew :desktopApp:packageDmg                   # DMG in desktopApp/build/compose/binaries

./gradlew :androidApp:assembleRelease              # APK in androidApp/build/outputs/apk/release
./tools/android-emulator.sh                        # APK im Emulator ohne Fenster + Screenshot

./gradlew :shared:desktopTest                      # Tests
./gradlew :desktopApp:renderScreens                # alle Bildschirme als PNG (Handy + Desktop)
```

Ohne eigenen Schlüssel (`~/.android/widder-release.jks` + `.pass`) wird das Release-APK mit dem
Debug-Schlüssel signiert.

Auf dem Mac: Dateien (auch ganze Ordner) einfach ins Fenster ziehen. Tastatur beim Abfragen:
Leertaste, 1–4, R (nochmal hören), E (bearbeiten), ⌘Z; im Training Enter = prüfen/weiter,
⌫ = letzten Akkord zurücklegen; Esc = zurück.
Auf Android: „Importieren“, oder aus einer anderen App „Teilen → Widder“ / „Öffnen mit“.

## Daten

Mac: `~/Documents/Widder`, Android: App-Speicher.
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
shared/src/desktopMain     javax.sound, afconvert (MP3/M4A/FLAC auf dem Mac)
androidApp/, desktopApp/   Einstiegspunkte
```

## Noch offen

- Import von Anki-Stapeln (`.apkg`), Lückentext, formatierter Text/Bilder auf Karten
- Sync zwischen Handy und Mac (bis dahin: Datenordner kopieren)
- Statistik-Ansicht
- Bei sehr großen Sammlungen (zehntausende Karten) auf SQLite umsteigen
