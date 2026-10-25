package widder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import widder.core.DeckKind
import widder.core.TileScope

/** What there is to do and which key does it, made from the current settings. */
@Composable
fun HelpScreen(vm: AppViewModel) {
    val prefix = vm.settings.prefix?.takeIf { vm.platform.keyboard }?.let(vm::keyLabel)
    Column(Modifier.fillMaxSize()) {
        TopBar("Modi & Tasten", vm::back) {
            SmallButton("Einstellungen", { vm.open(Screen.Settings) })
        }
        Centered {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionTitle("Modi")
                Panel {
                    Mode(
                        kindName(DeckKind.CARDS),
                        "Anhören oder lesen, umdrehen und selbst bewerten: Nochmal, Schwer, Gut oder Leicht.",
                        kindColor(DeckKind.CARDS),
                    )
                    Mode(
                        kindName(DeckKind.CHORDS),
                        "Anhören und die richtigen Kacheln in die Felder ziehen oder antippen, dann prüfen. " +
                            "Zwischen den richtigen liegen ähnliche falsche.",
                        kindColor(DeckKind.CHORDS),
                    )
                    Mode(
                        "Üben",
                        "Stimmgerät wie Choona, auch in Vierteltönen und anderen Mikroton-Teilungen, mit Gitarren-, Bass- und eigenen Stimmungen. Drone auf jedem Grundton (Sinus, Orgel, Fläche, Tanpura), Drums mit 17 Grooves und sechs Drumsets zum Mitspielen, ein Looper (einmal aufnehmen, läuft im Takt der Drums endlos weiter, mit Overdub obendrauf), ein Timer, der nach x Minuten alles ausschaltet – es spielt auch im Hintergrund weiter – und ein Vibrato-Trainer: Linie aus Punkten wie in Reaper (klicken, ziehen, ⇧ für steil, Doppelklick löscht), Vibrato darauf einstellen, anhören und nachmachen – aufgenommen und ausgewertet oder live ohne Aufnahme.",
                        Palette.practice,
                    )
                    Mode(
                        "Markieren",
                        "In den Stapellisten „Markieren“, Stapel antippen" + (prefix?.let { " (oder ⇧ + Klick, $it ⇧ + Nummer)" } ?: "") +
                            ", dann „Löschen“ – mehrere auf einmal.",
                        MaterialTheme.colorScheme.error,
                    )
                }

                if (vm.platform.keyboard) {
                    SectionTitle("Tastenkürzel")
                    Panel {
                        if (prefix == null) {
                            Text("Aus. In den Einstellungen einen Prefix wählen, z. B. ⌃B.")
                        } else {
                            Text(
                                "Erst $prefix, loslassen, dann die Taste.",
                                Modifier.padding(bottom = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            KeyRow("$prefix 1–9", "Stapel mit dieser Nummer wählen (sie steht vor dem Namen)")
                            KeyRow("$prefix ⇧1–9", "Stapel markieren / Markierung aufheben")
                            for (s in vm.shortcuts) KeyRow("$prefix ${s.key}", vm.describe(s.action))
                            Text(
                                "Lernen, Stapel öffnen, Neue Karte, Konfiguration … gelten für den gewählten oder offenen Stapel. " +
                                    "Tasten und Prefix lassen sich in den Einstellungen ändern.",
                                Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SectionTitle("Beim Lernen")
                    Panel {
                        Text(kindName(DeckKind.CARDS), fontWeight = FontWeight.SemiBold, color = kindColor(DeckKind.CARDS))
                        KeyRow("Leertaste", "Antwort zeigen, danach Gut")
                        KeyRow("1 2 3 4", "Nochmal · Schwer · Gut · Leicht")
                        KeyRow("R · E · M", "nochmal hören · bearbeiten · mischen")
                        KeyRow("K", "Klaviatur (nach der Antwort, bei MIDI)")
                        KeyRow(if (vm.platform.macKeys) "⌘Z" else "Strg+Z", "rückgängig")
                        Spacer(Modifier.height(10.dp))
                        Text(kindName(DeckKind.CHORDS), fontWeight = FontWeight.SemiBold, color = kindColor(DeckKind.CHORDS))
                        KeyRow("Leertaste · R", "anhören")
                        KeyRow("Enter", "prüfen, danach weiter")
                        KeyRow("⌫", "letzte Kachel zurücklegen")
                        KeyRow("1 2 3 4", "nach dem Prüfen: Nochmal · Schwer · Gut · Leicht")
                        KeyRow("E · M", "Audio bearbeiten · mischen")
                        KeyRow("K", "Klaviatur (nach dem Prüfen, bei MIDI)")
                        Spacer(Modifier.height(10.dp))
                        Text("Üben", fontWeight = FontWeight.SemiBold, color = Palette.practice)
                        KeyRow("Leertaste · D", "Drums · Drone an/aus")
                        KeyRow("T · ↑ ↓", "Tempo tippen · Tempo ±1")
                        KeyRow("V · G", "Vibrato · nur den Ton vorspielen")
                        KeyRow("R · Z", "Vibrato nachmachen (Aufnahme an/aus) · Zufall")
                        KeyRow("L", "Looper: aufnehmen · Loop setzen · an/aus")
                        KeyRow("O · ${if (vm.platform.macKeys) "⌘Z" else "Strg+Z"}", "Overdub an/aus · letzte Schicht weg")
                        Spacer(Modifier.height(10.dp))
                        KeyRow("Esc", "zurück")
                    }
                }

                SectionTitle("Kacheln")
                Panel {
                    if (vm.settings.tileScope == TileScope.OFF) {
                        Text("Aus.")
                    } else {
                        Text(
                            "Doppelt tippen oder halten auf einem Feld (${vm.settings.tileColumns} × ${vm.settings.tileRows}, ${vm.settings.tileScope.label}):",
                            Modifier.padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (tile in vm.tilesOf(learn = false).filter { !it.isEmpty }) {
                            KeyRow(tile.displayTitle, listOfNotNull(
                                tile.doubleTap.takeIf { !it.isNone }?.let { "2× " + vm.describe(it) },
                                tile.longPress.takeIf { !it.isNone }?.let { "halten: " + vm.describe(it) },
                            ).joinToString(" · "))
                        }
                    }
                    if (vm.settings.learnTilesOn) {
                        Text(
                            "Beim Lernen, von oben nach unten:",
                            Modifier.padding(top = 12.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (tile in vm.tilesOf(learn = true).filter { !it.isEmpty }) {
                            KeyRow(tile.displayTitle, listOfNotNull(
                                tile.doubleTap.takeIf { !it.isNone }?.let { "2× " + vm.describe(it) },
                                tile.longPress.takeIf { !it.isNone }?.let { "halten: " + vm.describe(it) },
                            ).joinToString(" · "))
                        }
                    }
                    SmallButton("Kacheln anpassen", { vm.open(Screen.Tiles) })
                }

                if (!vm.platform.keyboard) {
                    SectionTitle("Widgets & Schnellstart")
                    Panel {
                        Text(
                            "Auf dem Android-Startbildschirm kannst du beliebig viele Widder-Widgets ablegen: " +
                                "für die A-Drone ohne Quinte (startet sofort), den Üben-Bildschirm, " +
                                "die Akkordfolgen oder jedes einzelne Lerndeck mit Anzeige der fälligen Karten.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun Mode(title: String, text: String, color: Color) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeyRow(keys: String, label: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyHint(keys, Modifier.widthIn(min = 92.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
