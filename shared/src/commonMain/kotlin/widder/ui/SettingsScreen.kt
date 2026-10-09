package widder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import widder.core.KeyStroke
import widder.core.Reward
import widder.core.Shortcuts

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    val r = s.reward
    Column(Modifier.fillMaxSize()) {
        TopBar("Einstellungen", vm::back) {
            SmallButton("Modi & Tasten", { vm.open(Screen.Help) })
        }
        Centered {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionTitle("Bildbelohnung")
                Panel {
                    SwitchRow("Bild nach und nach freischalten", "Aus: kein Bild auf dem Startbildschirm", r.enabled) { on ->
                        vm.updateSettings { it.copy(reward = it.reward.copy(enabled = on)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.image == null) "Kein Bild gewählt" else "Bild gewählt", Modifier.weight(1f))
                        SmallButton(if (r.image == null) "Bild wählen" else "Anderes Bild", vm::pickRewardImage)
                        if (r.image != null) SmallButton("Entfernen", vm::removeRewardImage, color = MaterialTheme.colorScheme.error)
                    }
                    Label("Aufteilung")
                    Choices(listOf(2 to 2, 3 to 2, 3 to 3, 4 to 3, 5 to 4), r.columns to r.rows, { "${it.first} × ${it.second}" }) { (c, rows) ->
                        vm.updateSettings { it.copy(reward = it.reward.copy(columns = c, rows = rows)) }
                    }
                    Label("Lernzeit pro Teil")
                    Choices(listOf(30, 60, 5 * 60, 10 * 60, 20 * 60, 30 * 60, 60 * 60), r.secondsPerTile, { formatMinutes(it) }) { sec ->
                        vm.updateSettings { it.copy(reward = it.reward.copy(secondsPerTile = sec)) }
                    }
                    Label("Stärke der Unschärfe")
                    Choices(listOf(1, 2, 3, 4), r.blur, { listOf("leicht", "mittel", "stark", "sehr stark")[it - 1] }) { b ->
                        vm.updateSettings { it.copy(reward = it.reward.copy(blur = b)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("Jeden Tag neu anfangen", "Der Lerntag wechselt um 4 Uhr morgens, wie in Anki", r.resetDaily) { on ->
                        vm.updateSettings { it.copy(reward = it.reward.copy(resetDaily = on)) }
                    }
                    val p = vm.rewardProgress()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Stand: ${Reward.unlocked(p, r)} von ${Reward.tileCount(r)} Teilen · ${formatMinutes(p.seconds)} gelernt · " +
                                "komplett nach ${formatMinutes(Reward.tileCount(r) * r.secondsPerTile)}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SmallButton("Zurücksetzen", vm::resetReward, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "Es zählt nur die Zeit beim Abfragen und Trainieren, solange Widder im Vordergrund ist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionTitle("Notennamen")
                Panel {
                    SwitchRow("Deutsche Notennamen", "H = B-natürlich, B = B-Dur/Bb. Aus: B ist H. „H“, „Fis“, „Es“ versteht Widder immer.", s.germanNotation) { on ->
                        vm.updateSettings { it.copy(germanNotation = on) }
                    }
                    Label("Stufen (römische Zahlen)")
                    Choices(
                        widder.core.NumeralMode.entries,
                        s.numerals,
                        { when (it) { widder.core.NumeralMode.OFF -> "aus"; widder.core.NumeralMode.WITH_NAME -> "I (A)"; widder.core.NumeralMode.ONLY -> "nur I" } },
                    ) { mode -> vm.updateSettings { it.copy(numerals = mode) } }
                    Text(
                        "Die Tonart erkennt Widder aus den Akkorden einer Karte; festlegen lässt sie sich pro Stapel oder Karte.",
                        Modifier.padding(top = 6.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow("Moll als „min“ schreiben", "Wenn Widder selbst Akkorde benennt (MIDI-Erkennung): Fmin statt Fm", s.minorAsMin) { on ->
                        vm.updateSettings { it.copy(minorAsMin = on) }
                    }
                }

                SectionTitle("Wiedergabe")
                Panel {
                    SwitchRow("Audio automatisch abspielen", "Beim Zeigen einer Karte und bei jeder Übung", s.autoplay) { on ->
                        vm.updateSettings { it.copy(autoplay = on) }
                    }
                }

                SectionTitle("Kacheln")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${s.tileColumns} × ${s.tileRows} Felder, ${s.tileScope.label} – doppelt tippen oder halten",
                            Modifier.weight(1f),
                        )
                        SmallButton("Anpassen", { vm.open(Screen.Tiles) })
                    }
                }

                if (vm.platform.keyboard) KeyboardSettings(vm)

                if (vm.platform.canDropFiles) {
                    SectionTitle("Tastatur beim Lernen")
                    Panel {
                        Text("Flash Cards: Leertaste = Antwort zeigen / Gut, 1–4 = Nochmal/Schwer/Gut/Leicht, R = nochmal hören, E = bearbeiten, ⌘Z / Strg+Z = rückgängig")
                        Spacer(Modifier.height(6.dp))
                        Text("Multiple Choice: Leertaste = anhören, Enter = prüfen / weiter, ⌫ = letzten Akkord zurücklegen, E = Audio bearbeiten")
                        Spacer(Modifier.height(6.dp))
                        Text("Überall: Esc = zurück")
                    }
                }

                SectionTitle("Daten")
                Panel {
                    Text("Deine Sammlung liegt in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.dataLocation, fontWeight = FontWeight.Medium)
                    Text(
                        "collection.json (Stapel und Karten), progress.json (Lernstand) und media/ (Audio, MIDI, Bild).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** The prefix and the shortcuts after it. */
@Composable
private fun KeyboardSettings(vm: AppViewModel) {
    val prefix = vm.settings.prefix
    SectionTitle("Tastenkürzel")
    Panel {
        Text("Prefix", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        val options: List<KeyStroke?> = (Shortcuts.PREFIXES + listOfNotNull(prefix)).distinct() + listOf(null)
        Choices(options, prefix, { it?.let(vm::keyLabel) ?: "aus" }) { p -> vm.updateSettings { it.copy(prefix = p) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Wie in tmux: erst den Prefix, loslassen, dann eine Taste.",
                Modifier.weight(1f).padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SmallButton("Eigenen drücken …", { vm.capturingPrefix = true })
        }
        if (prefix != null) {
            Spacer(Modifier.height(8.dp))
            for (s in vm.shortcuts) {
                Row(Modifier.fillMaxWidth().clickable { vm.shortcutEdit = ShortcutEdit(s.key) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyHint("${vm.keyLabel(prefix)} ${s.key}")
                    Spacer(Modifier.width(12.dp))
                    Text(vm.describe(s.action), Modifier.weight(1f))
                    SmallButton("Entfernen", { vm.deleteShortcut(s.key) }, color = MaterialTheme.colorScheme.error)
                }
            }
            Row {
                SmallButton("+ Kürzel", { vm.shortcutEdit = ShortcutEdit(null) })
                Spacer(Modifier.weight(1f))
                SmallButton("Standard", vm::resetShortcuts, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            Chip(label(o), MaterialTheme.colorScheme.primary, filled = o == selected, modifier = Modifier.clickable { onPick(o) })
        }
    }
}
