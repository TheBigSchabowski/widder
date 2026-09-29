package widder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import widder.core.Reward

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    val r = s.reward
    Column(Modifier.fillMaxSize()) {
        TopBar("Einstellungen", vm::back)
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

                if (vm.platform.canDropFiles) {
                    SectionTitle("Tastatur")
                    Panel {
                        Text("Abfragen: Leertaste = Antwort zeigen / Gut, 1–4 = Nochmal/Schwer/Gut/Leicht, R = nochmal hören, E = bearbeiten, ⌘Z = rückgängig")
                        Spacer(Modifier.height(6.dp))
                        Text("Akkord-Training: Leertaste = anhören, Enter = prüfen / weiter, ⌫ = letzten Akkord zurücklegen, E = Audio bearbeiten")
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

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            Chip(label(o), MaterialTheme.colorScheme.primary, filled = o == selected, modifier = Modifier.clickable { onPick(o) })
        }
    }
}
