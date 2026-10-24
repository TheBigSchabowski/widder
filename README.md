# Widder

Spaced-Repetition-Lernapp für Musik: Anki-artige Flash Cards, ein kleiner Audio-Editor und
Gehörtraining als Multiple Choice per Drag & Drop. Eine Codebasis (Kotlin Multiplatform + Compose
Multiplatform) für **Android**, **macOS** und **Linux**.

## Die zwei Wege

**Flash Cards** – Stapel wie in Anki: Neu / Lernen / Fällig, Lernschritte 1 Min → 10 Min,
danach Tagesintervalle aus FSRS (Nochmal / Schwer / Gut / Leicht mit Intervall-Vorschau),
Tageslimits pro Stapel, Rückgängig, Aussetzen, Suche. Jede Seite einer Karte kann Audio haben:
aufnehmen, Datei wählen, MIDI abspielen.

**Multiple Choice** – Dateien hinzufügen, der Dateiname ist die Lösung:

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

**Mischen:** Neue Karten und Wiederholungen kommen in jeder Sitzung in einer neuen Reihenfolge,
nicht in der, in der sie angelegt wurden – sonst lernt man bei Intervallen die Reihenfolge statt
der Klänge. Abschalten pro Stapel in den Stapel-Optionen („Mischen“). Der Knopf „Mischen“ beim
Lernen und im Training (Taste M) mischt sofort neu und legt die aktuelle Karte zurück.

**Startkits** (vorinstalliert, jeweils als Flash Cards und als Multiple Choice, jedes mit eigenem
Ordner; ein gelöschtes lässt sich in der Stapelliste wieder „Hinzufügen“):

| Startkit | Inhalt |
|---|---|
| Intervalle gleichzeitig | alle 24 Intervalle von der kleinen Sekunde bis zur Quintdezime, beide Töne zusammen |
| Intervalle nacheinander | dieselben, erst der tiefe, dann der hohe Ton |
| Astra Deck (Akkordarten) | Amaj, Amin, Amaj7, A7, Amin7, Amaj(add9), Amin(add9), Adim, A5, Amaj(no5), Amin(no5), A-Oktave – in Grundstellung und allen Umkehrungen (34 Karten) |

Im Astra Deck ist die Lösung immer nur der Akkord („Amaj7“, nicht „Amaj7/Cis“). Welche
Umkehrung erklingt, zeigt nur der Karten-Editor („Was das Audio spielt“), nie das Lernen.
`Amaj(no5)` und `Amin(no5)` sind die Terz-Zweiklänge zu Dur und Moll (A–Cis, A–C); umgekehrt
werden sie zur Sexte, A5 zur Quarte. Verstanden wird auch `A-Durterz` / `A-Mollterz`.

Ab 1.9.0 bekommen bestehende Sammlungen die neuen Startkits einmalig dazu; die alten (A-Akkorde,
Akkordtypen, Akkordfolgen, Stufen und Modi) bleiben, bis man sie löscht.

**Mehrere Stapel löschen:** In der Stapelliste „Markieren“, Stapel antippen (oder „Alle“; am
Computer geht auch ⇧ + Klick auf einen Stapel, ohne vorher „Markieren“ zu drücken), dann
„Löschen“ – Widder fragt einmal nach und zeigt, welche Stapel gehen.

**Tags:** Hat ein Stapel getaggte Karten (Karten-Editor → Schlagwörter), stehen die Tags unter ihm
in der Stapelliste (und in den Stapel-Optionen). Antippen lernt nur noch Karten mit diesem Tag –
mehrere gehen zusammen, „Alle“ hebt den Filter auf. Bei Multiple Choice kommen dann auch die
falschen Kacheln nur aus den gewählten Karten.

Jedes Intervall beginnt auf einem anderen Ton, damit die Tonhöhe nichts verrät; als falsche
Kacheln kommen die Nachbarn (kleine statt große Terz) und dasselbe Intervall eine Oktave weiter.
Intervalle werden auch in Dateinamen verstanden (`kleine Terz.mid`, `große Sexte.wav`), und eine
MIDI-Datei ohne Lösung im Namen, die aus genau zwei Tönen besteht, wird als Intervall erkannt.
In den Stapel-Optionen lässt sich die Stufen-Anzeige pro Stapel festlegen (aus / I (A) / nur I).

Verstanden werden u. a. `m min - moll`, `7 maj7 M7 Δ`, `m7 m7b5 ø dim dim7 aug sus2 sus4 6 9 add9`,
Slash-Akkorde (`C/E`) und deutsche Namen (`H`, `Fis`, `Es`, `As`, `Cism`, `Des-Dur`).
In den Einstellungen: „Deutsche Notennamen“ (B = Bb).

## Üben: Stimmgerät, Drone, Drums, Looper und Vibrato

Der dritte Weg auf dem Startbildschirm hat keine Karten – nur Begleitung zum Mitspielen. Drone und
Drums laufen unabhängig voneinander (jeweils eigener Play-Knopf) und lassen sich während des
Spielens umstellen; Wechsel werden weich überblendet.

| Teil | Einstellungen |
|---|---|
| **Drone** | Grundton (alle 12), Oktave 1–4, Klang **Sinus / Orgel / Fläche / Tanpura**, Quinte und Oktave dazu, Lautstärke, Stimmton a′ = 432 / 440 / 442 / 443 Hz |
| **Drums** | Tempo 40–240 (Regler, ±1, Tempo tippen), Taktart 4/4 · 3/4 · 2/4 · 6/8, 17 Grooves, Drumset, Metronom-Klick dazu, Lautstärke |

Die Drum-Machine ist die aus [NicheLooper](https://github.com/TheBigSchabowski/NicheLooper):
dieselben 17 Grooves (Rock, Pop, Straight 16, Funk, Ballad, Four on the Floor, Shuffle, Bossa,
Waltz, Jazz Waltz, Waltz Ballad, March, Polka, 2/4 Rock, 6/8 Basic, 6/8 Blues, 6/8 March) mit
Akzenten und Ghost Notes, dieselbe Klick-Hierarchie (G6 auf der Eins, E6 auf Nebenakzenten, C6),
BPM in 6/8 zählt Achtel. Drumsets: **Akustik** (die Black-Pearl-Samples aus NicheLooper),
**Synth** (NicheLoopers synthetisches Kit), **808**, **Besen** (Filzkick, Besen, Ride),
**Cajón** (Bass, Slap, Shaker) und **Nur Klick**.

Tanpura zupft wie das Original vier Saiten im Kreis: Pa (die Quinte, ohne „Quinte dazu“ die
Oktave), zweimal das hohe Sa, dann das tiefe Sa. Die Einstellungen bleiben gespeichert, gespielt
wird nur, solange „Üben“ offen ist. Am Mac/unter Linux: Leertaste = Drums an/aus, D = Drone, T =
Tempo tippen, ↑ ↓ = Tempo, L = Looper, O = Overdub, ⌘Z = letzte Overdub-Schicht weg. Übezeit zählt nicht für die Bildbelohnung.

**Hintergrund und Timer:** Drums, Drone und Loop spielen weiter, wenn man die App verlässt oder
den Bildschirm ausschaltet (Android: Benachrichtigung „Widder übt“ mit Stopp-Knopf; ein Wisch aus
den letzten Apps beendet den Ton). Nur das Verlassen von „Üben“ in der App stoppt sie. Der **Timer**
(5–60 min) schaltet alles aus, gezählt ab dem ersten Ton.

### Looper

Einmal aufnehmen, dann wiederholt es sich endlos – z. B. Gitarre übers Mikrofon. Rote Taste (oder
**L**) startet die Aufnahme, nochmal drücken setzt den Loop, danach schaltet sie ihn an und aus.

- **Laufen die Drums**, ist der Loop an ihren Takt gebunden: er beginnt auf der nächsten Eins (oder
  der gerade vergangenen, wenn man knapp zu spät gedrückt hat) und endet auf einer Taktlinie – wer
  kurz nach der Eins „Loop setzen“ drückt, schließt dort, sonst nimmt Widder bis zur nächsten Eins
  weiter auf. Der Loop bleibt im Groove, auch wenn die Drums aus- und wieder angehen.
- **Ohne Drums** ist der Loop genau so lang, wie man gespielt hat.
- **Overdub** (rote „+“-Taste neben dem Loop, oder **O**): während der Loop läuft dazuspielen – Runde
  für Runde kommt es obendrauf, bis man die Taste wieder drückt. Jede Overdub-Runde ist eine
  **Schicht**; „Letzte Schicht weg“ (⌘Z) nimmt sie wieder heraus (bis zu 8 Schritte). Das Mikrofon
  wird dabei an genau die Stelle im Loop gelegt, die man beim Spielen gehört hat, auch bei
  geändertem Tempo eines Loops im Takt der Drums; Anfang und Ende jedes Overdubs werden kurz
  eingeblendet, damit nichts knackt. Ein angehaltener Loop startet mit dem Overdub.
- Die Naht wird über 15 ms überblendet (was nach dem Ende kam, läuft in den Anfang), damit nichts
  knackt. **Latenz-Ausgleich** (Standard 150 ms) gleicht Ausgabe- und Mikrofonverzögerung aus –
  für Loops im Takt der Drums wie für Overdubs. **„Latenz messen“** spielt sechs Klicks und hört
  über das Mikrofon, wann sie ankommen (ohne Kopfhörer, kurz still sein); am Handy ist die
  Verzögerung meist deutlich größer als am Mac. Zum Spielen dann Kopfhörer, sonst nimmt das
  Mikrofon Drums und Loop mit auf.
- Auf Android läuft eine Aufnahme oder ein Overdub auch bei ausgeschaltetem Bildschirm weiter (die
  Benachrichtigung „Widder übt“ nutzt dann das Mikrofon).
- „Als WAV speichern“ (mit allen Schichten), „Neu aufnehmen“, „Löschen“, Lautstärke.

### Stimmgerät

Wie [Choona](https://github.com/rohankhayech/Choona), mit Mikrotönen: **chromatisch** in einer wählbaren
Teilung der Oktave – Halbtöne, **Vierteltöne**, Dritteltöne, Achteltöne oder 19-, 22-, 31-, 53-EDO
(der Ton heißt dann z. B. „A +50 ct“) – oder auf die **Saiten einer Stimmung**: Gitarre (Standard, Drop D,
Halbton/Ganzton tiefer, Drop C, DADGAD, Open D/G/E, 7-Saiter), Bass (4/5 Saiten, Drop D), Ukulele,
Bariton-Ukulele, Geige/Mandoline, Bratsche, Cello. Die Saite wird erkannt; antippen spielt sie vor und
hält sie fest. **Eigene Stimmungen** als Notenliste, auch mikrotonal: `D2 A2 D3 G3 A3+50 D4`. Abweichung
in Cent oder Halbtönen, Erkennung bis 28 Hz (tiefe H-Saite am Bass).

**Stimmton a′** gilt für Stimmgerät, Drone und Vibrato: als Chip oder frei eingetippt (300–500 Hz,
auch 438,5), eigene Standards lassen sich speichern und entfernen. Die Drone lässt sich in der
gewählten Teilung **mikrotonal verstimmen** (z. B. einen Viertelton höher).

### Visualisieren

„Wo singe ich?“ zeigt laufend die eigene Tonhöhe – Stimme oder Instrument – auf einer Klavierrolle
(5, 10, 20 oder 60 s Zeitfenster, neueste Stelle rechts), mit Ton, Cent-Abweichung und Hz. Die Töne
der Drone (Grundton, Quinte) sind als lila Linien hervorgehoben, Mikroton-Stufen als feine Linien.

### Audio-Geräte (Mac, Linux und Android)

In den Einstellungen: **Eingang** (eingebautes Mikrofon oder Audio-Interface), **Kanal** des Eingangs
(alle gemischt oder z. B. „Eingang 1“ für die Gitarre am Interface), **Ausgang**, dazu ein
**Eingangstest** mit Pegel und erkanntem Ton. Am Handy: Mikrofon, Lautsprecher, Kopfhörer, Headset,
Bluetooth-Kopfhörer und ein Interface an USB-C (nach dem Einstecken „Geräte neu suchen“); kennt ein
Eingang den gewählten Kanal nicht, nimmt Widder alle gemischt. Die Mac-App hat jetzt die Berechtigung
`com.apple.security.device.audio-input` – ohne sie lieferte macOS nur Stille, ohne zu fragen.

### Vibrato-Trainer

Eine Vorgabe anhören und nachmachen – aufnehmen und auswerten oder **live mitspielen**: dann läuft die
eigene Tonhöhe ohne Aufnahme Durchgang für Durchgang über die Vorgabe (mit Cursor), und Geschwindigkeit,
Breite und Richtung der letzten 3 Sekunden stehen darunter. Die Vorgabe ist eine **Linie** mit einem
**Vibrato** obendrauf:

- **Linie** wie eine Hüllkurve in Reaper: Vorlagen *Gerade, Steigend, Fallend, Von unten* (Scoop),
  *Fall-off, Bending, Bogen*, oder unter „Linie bearbeiten“ selbst: **klicken** setzt einen Punkt,
  **ziehen** verschiebt ihn (weicher Übergang), **mit ⇧ Shift ziehen** macht den Weg dorthin **steil**,
  **Doppelklick** oder langes Drücken löscht. Für den gewählten Punkt: Übergang *weich / gerade / steil*.
  Punkte rasten auf Töne ein (bzw. auf die Mikroton-Teilung, z. B. Vierteltöne); die Achse zeigt
  Notennamen oder Hz.
- **Vibrato**: **Breite** 0–200 Cent (vom tiefsten zum höchsten Punkt, 0 = nur die Linie),
  **Geschwindigkeit** 2–9 Hz, **Vibrato ab** (setzt nach x Sekunden ein und schwillt an) und
  **Richtung** – *nach oben* (die Linie ist der tiefste Punkt, wie ein Bending), *um den Ton* oder
  *nach unten*.
- Dazu Ton (alle 12, Oktave 2–5), Klang **Streicher / Stimme / Flöte / Sinus** und Länge 2–8 s.

**Eigenes Vibrato einsingen:** Nach einer Aufnahme „Als Vorgabe übernehmen“ (oder im Live-Modus „Die
letzten 3 Sekunden …“) macht aus der gesungenen Tonhöhenkurve die Linie – nur so viele Punkte, dass sie
auf 3 Cent genau bleibt, weich zwischen Berg und Tal – auf dem nächsten Ton. Widder spielt sie dann mit
dem gewählten Klang nach (jetzt auch **Orgel**), und sie lässt sich im Editor weiter bearbeiten.

Die Tonhöhe wird alle 10 ms Audio gemessen, nach der Uhr der Samples selbst: auch Geräte, die ihre
Samples in großen Paketen liefern (wie manche am Mac), ergeben eine glatte Kurve.

„Nur den Ton“ spielt die Note gerade. **Zufall** würfelt Vibrato, Einsatz und (jedes zweite Mal)
eine Linie, verdeckt alles und spielt es vor – dann nach Gehör nachmachen; aufgedeckt wird nach der
Auswertung (oder mit „Aufdecken“).

Beim Aufnehmen zeigt Widder die eigene Tonhöhe live (Tonhöhenerkennung mit YIN, eine Oktave höher
oder tiefer zählt genauso). Danach misst er Geschwindigkeit (aus der Autokorrelation der
Tonhöhenkurve), Breite und Richtung des Vibratos, die Tonhöhe im Schnitt und den **Verlauf** (wie weit
die Mittellinie von der Linie der Vorgabe abweicht, 95 % der Zeit), vergleicht mit der Vorgabe und gibt
Punkte (0–100). Der Versuch lässt sich anhören. Tasten: V / G = Vibrato / Ton vorspielen, R = Aufnahme
an/aus, Z = Zufall.

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

**Startkits:** Auf dem Mac liegen die Dateien automatisch in
`~/Documents/Widder/Astra Deck` usw., unter Linux im Dokumente-Ordner des Benutzers
(z. B. `~/Dokumente/Widder/Astra Deck`). Auf Android „Ordner“ tippen und einen Ordner wählen
(am besten im Dialog einen neuen anlegen, z. B. `Documents/Widder Startkit`) – Android lässt
Apps nur in Ordner schreiben, die man selbst ausgesucht hat.

## Akkorde auf Flash Cards und Stufen

Auch eine Flash Card kann Akkorde haben (Feld „Akkorde“ im Editor). Sie erscheinen auf der
Rückseite, und „Als Audio erzeugen“ macht aus ihnen gleich den Klang der Vorderseite. Die
Startkits gibt es deshalb auch als Flash Cards: vorne die Frage und der Klang, hinten Akkord
oder Intervall.

Akkorde werden mit ihrer **Stufe** gezeigt: `I (A)`, `IV (D)`, `bVII (G)`, `vi (F#m)`,
Umkehrungen als `I/3` (Terz im Bass) und `I/5` (Quinte im Bass). Die Tonart erkennt Widder aus den
Akkorden einer Karte; nur welche Akkorde vorkommen zählt, nicht die Reihenfolge – sonst würde im
Training die „I“-Kachel verraten, was zuerst kommt. Festlegen lässt sie sich pro Stapel
(Stapel-Optionen) oder Karte. In den Einstellungen: Stufen aus / „I (A)“ / nur „I“.

Für alles ohne Akkorde oder feste Tonart gibt es das Feld **„Eigener Text statt Akkorden und
Stufen“** (z. B. `Son Clave 3-2`): Ist es ausgefüllt, zeigt die Karte diesen Text statt der
Akkord-Chips; bei Multiple Choice erscheint er nach dem Prüfen, die Stufen fallen weg.

Bei MIDI-Audio klappt **„Auf der Klaviatur zeigen“** eine kleine Klaviatur auf: welche Tasten die
Datei drückt, Akkord für Akkord, mit Notennamen (C4 = eingestrichenes C) – zum Nachschauen, ob die
Lösung stimmt, nicht zum Abfragen. Sie erscheint erst mit der Antwort (Abfrage) bzw. nach dem
Prüfen (Training) und im Editor unter der MIDI-Datei; ein Tipp auf eine Klaviatur spielt den Griff.
Einmal geöffnet bleibt sie für die nächsten Karten offen.

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

## Kacheln und Tastenkürzel

Über dem Bildschirm liegt ein unsichtbares Raster aus **Kacheln** (Standard 2 × 2, einstellbar bis
3 × 4). Normales Tippen bedient wie immer, was darunter liegt. **Doppelt tippen** oder
**gedrückt halten** auf einer Kachel löst deren Aktion aus – auch über Knöpfen und Karten:
Beim Halten wird der Knopf darunter abgebrochen, er löst nicht aus. Beim Doppeltippen erreicht
das erste Tippen noch den Knopf (ein einzelnes Tippen lässt sich nicht rechtzeitig von einem
doppelten unterscheiden) – auf Knöpfen ist Halten daher die sichere Geste. Wischen und Scrollen
lösen nichts aus.

Pro Kachel einstellbar („Kacheln anpassen“ oder Einstellungen → Kacheln): Name, Farbe und je eine
Aktion für doppelt Tippen und Halten:

| Aktion | was passiert |
|---|---|
| Flash Cards / Multiple Choice öffnen | die Stapelübersicht |
| Lernen | Abfrage bzw. Training des Stapels |
| Stapel öffnen | die Kartenliste |
| Neue Karte | der Karten-Editor |
| Aufnehmen → Karte | nimmt sofort auf; danach öffnen Schnitt und Editor, aus der Aufnahme wird eine Karte |
| Dateien importieren | Dateiauswahl, direkt in den Stapel |
| Konfiguration | die Stapel-Optionen des gewählten oder offenen Stapels, sonst die Einstellungen |
| Bild ansehen, Einstellungen, Startbildschirm, Modi & Tasten | |
| Karte bearbeiten | beim Lernen: die Karte auf dem Bildschirm im Editor |
| Klaviatur | Klaviatur unter der MIDI-Lösung auf-/zuklappen (erst mit der Antwort) |
| Speichern → zurück zur Karte | im Editor, der beim Lernen geöffnet wurde: speichert und zeigt wieder die Karte |

Bei Aktionen mit Stapel legt man pro Geste einen festen Stapel fest – oder nicht: Dann nimmt die
Aktion den gerade offenen Stapel und fragt sonst nach. Die Kacheln hören nur auf dem
Startbildschirm, überall (außer im Karten-Editor und bei Multiple Choice, die die Gesten selbst
brauchen) oder gar nicht. Eine ausgelöste Kachel leuchtet kurz mit ihrer Aktion auf; „Felder
andeuten“ zeigt das Raster dauerhaft. Standard: oben links Halten = Lernen, oben rechts Halten =
Neue Karte, unten links Halten = Flash Cards, unten rechts Halten = Aufnehmen → Karte.

**Beim Lernen** (Abfrage, Training und der Editor, der von dort geöffnet wird) gilt ein eigenes
Raster mit einer Spalte, standardmäßig drei Reihen: **oben doppelt** = Karte bearbeiten, **Mitte
doppelt** = Klaviatur, **unten doppelt** = speichern und zurück zur Karte. Ein Feld greift nur, wo
seine Aktion gerade etwas tun kann (z. B. die Klaviatur erst mit der Antwort, „zurück“ nur im
Editor) – sonst geht das Tippen wie immer durch. Abschaltbar und frei belegbar unter Kacheln →
„Beim Lernen“. Am Desktop klappt **K** die Klaviatur auf und zu.

**Tastenkürzel (Mac und Linux)** wie in tmux: erst den Prefix **⌃B** (Linux: Strg+B), loslassen, dann eine Taste. Nach dem
Prefix erscheint unten eine Übersicht aller Tasten (auch anklickbar). Buchstaben sind frei belegbar (Standard: F Flash Cards,
M Multiple Choice, Y Konfiguration, L Lernen, N Neue Karte, R Aufnehmen, I Importieren, O Stapel
öffnen, B Bild, S Einstellungen, H Start, ? Modi & Tasten).

Die Ziffern wählen Stapel: Jeder Stapel trägt vor dem Namen eine Nummer, erst die Flash Cards,
dann Multiple Choice. **⌃B 3** zeigt Stapel 3 hervorgehoben in seiner Liste; danach gelten
⌃B L, O, N, R, I und Y für ihn. **⌃B ⇧3** markiert ihn zum Löschen (nochmal = ab), auch über
beide Listen hinweg. Nummern gibt es für die ersten neun Stapel.

Alle Modi, Kürzel und Kacheln stehen auf einer Seite: „?“ auf dem Startbildschirm, „Modi & Tasten“
in den Einstellungen oder ⌃B ?. Der
Prefix ist wählbar (⌃B, ⌃A, ⌃Leertaste, ⌘K) oder selbst einzudrücken („Eigenen drücken …“,
z. B. ⌥Leertaste oder F5) oder ganz aus.

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
bei Flash Cards: Leertaste, 1–4, R (nochmal hören), E (bearbeiten), M (mischen), ⌘Z bzw. Strg+Z; bei Multiple Choice
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
  audio/   PCM, WAV, Synth, Effekte (Filter, Freeverb), Player/Recorder-Schnittstellen,
           Übe-Engine (Drone, Drumsets, Grooves aus NicheLooper)
  ui/      Compose-Oberfläche und AppViewModel
shared/src/jvmCommonMain   Dateien, Zeit, Audio-Threads (für beide Plattformen), Drum-Samples
shared/src/androidMain     AudioTrack, AudioRecord, MediaCodec (MP3/M4A/FLAC/OGG)
shared/src/desktopMain     javax.sound; Dateiformate: afconvert (Mac), Java-Decoder (MP3, M4A/AAC,
                           FLAC, OGG), ffmpeg falls vorhanden; Ordner-Dialoge, XDG-Verzeichnisse
androidApp/, desktopApp/   Einstiegspunkte
```

## Drittanbieter-Inhalte

- **Drum-Samples** des Drumsets „Akustik“ (`shared/src/jvmCommonMain/resources/drums/*.wav`) – aus
  dem Hydrogen-Drumkit **„The Black Pearl 1.0“** von Glen MacArthur (AVL Drumkits), lizenziert unter
  der **GPL**, übernommen aus NicheLooper. Quelle und Zuordnung der Einzeldateien:
  `drums/ATTRIBUTION.txt`. Beim Weiterverteilen gelten für diese Samples die GPL-Bedingungen.

## Noch offen

- Import von Anki-Stapeln (`.apkg`), Lückentext, formatierter Text/Bilder auf Karten
- Sync zwischen Handy und Mac (bis dahin: Datenordner kopieren)
- Statistik-Ansicht
- Bei sehr großen Sammlungen (zehntausende Karten) auf SQLite umsteigen
