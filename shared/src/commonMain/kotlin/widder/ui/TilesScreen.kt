package widder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import widder.core.Action
import widder.core.ActionKind
import widder.core.DeckKind
import widder.core.GestureTile
import widder.core.Shortcut
import widder.core.Shortcuts
import widder.core.TileColor
import widder.core.TileScope

/** The gesture grid: where it listens, how it is split, and a preview to pick a field from. */
@Composable
fun TilesScreen(vm: AppViewModel) {
    val st = vm.settings
    Column(Modifier.fillMaxSize()) {
        TopBar("Kacheln", vm::back, subtitle = "Doppelt tippen und halten") {}
        Centered {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Panel {
                    Text(
                        "Der Bildschirm ist in unsichtbare Felder aufgeteilt. Normales Tippen bedient wie immer, was dort liegt. " +
                            "Doppelt tippen oder gedrückt halten löst die Aktion des Feldes aus – auch über Knöpfen und Karten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Aktiv", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    Choices(TileScope.entries, st.tileScope, { it.label }) { scope -> vm.updateSettings { it.copy(tileScope = scope) } }
                    Text("Spalten", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    Choices(listOf(1, 2, 3), st.tileColumns, { "$it" }) { n -> vm.updateSettings { it.copy(tileColumns = n) } }
                    Text("Reihen", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    Choices(listOf(1, 2, 3, 4), st.tileRows, { "$it" }) { n -> vm.updateSettings { it.copy(tileRows = n) } }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("Felder andeuten", "Zarte Umrisse und Namen über dem Bildschirm", st.showTiles) { on -> vm.updateSettings { it.copy(showTiles = on) } }
                }
                SectionTitle("Felder – zum Bearbeiten antippen")
                TileGridPreview(vm)
                Row(Modifier.padding(top = 12.dp)) {
                    Spacer(Modifier.weight(1f))
                    SmallButton("Standard wiederherstellen", {
                        vm.confirm = Confirm("Kacheln zurücksetzen?", "Das Raster wird wieder 2 × 2 mit den Standard-Aktionen.", "Zurücksetzen") { vm.resetTiles() }
                    }, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** The grid as on the screen, a little smaller; each field says what its gestures do. */
@Composable
private fun TileGridPreview(vm: AppViewModel) {
    val columns = vm.settings.tileColumns
    val tiles = vm.tiles
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in tiles.indices.chunked(columns)) {
            Row(Modifier.height(if (vm.settings.tileRows > 2) 120.dp else 150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in row) {
                    val tile = tiles[i]
                    val color = tileColor(tile.color)
                    Surface(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).clickable { vm.open(Screen.EditTile(i)) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (tile.isEmpty) MaterialTheme.colorScheme.surface else color.copy(alpha = 0.16f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (tile.isEmpty) MaterialTheme.colorScheme.outlineVariant else color.copy(alpha = 0.6f)),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                tile.displayTitle,
                                fontWeight = FontWeight.SemiBold,
                                color = if (tile.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.weight(1f))
                            for ((label, action) in listOf("2×" to tile.doubleTap, "Halten" to tile.longPress)) {
                                Text(
                                    "$label: ${if (action.isNone) "–" else vm.describe(action)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One field: name, colour and its two gestures. Changes count only with "Speichern". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTileScreen(vm: AppViewModel, index: Int) {
    var tile by remember(index) { mutableStateOf(vm.tiles.getOrNull(index) ?: GestureTile()) }
    val columns = vm.settings.tileColumns
    val where = "Reihe ${index / columns + 1}, Spalte ${index % columns + 1}"
    Column(Modifier.fillMaxSize()) {
        TopBar("Kachel bearbeiten", vm::back, subtitle = where) {
            SmallButton("Speichern", {
                vm.saveTile(index, tile)
                vm.back()
            }, color = tileColor(tile.color))
        }
        Centered {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SectionTitle("Doppelt tippen")
                Panel { ActionPicker(vm, tile.doubleTap) { tile = tile.copy(doubleTap = it) } }
                SectionTitle("Gedrückt halten")
                Panel { ActionPicker(vm, tile.longPress) { tile = tile.copy(longPress = it) } }
                SectionTitle("Aussehen")
                Panel {
                    OutlinedTextField(
                        tile.title,
                        { tile = tile.copy(title = it) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        placeholder = { Text(tile.copy(title = "").displayTitle) },
                        singleLine = true,
                    )
                    Text("Farbe", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in TileColor.entries) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(tileColor(c))
                                    .border(3.dp, if (c == tile.color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                    .clickable(onClickLabel = c.name) { tile = tile.copy(color = c) },
                            )
                        }
                    }
                }
                SmallButton("Kachel leeren", { tile = GestureTile(color = tile.color) }, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** What to do, and – where it needs one – with which deck. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionPicker(vm: AppViewModel, action: Action, allowNone: Boolean = true, onChange: (Action) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in ActionKind.entries) {
            if (kind == ActionKind.NONE && !allowNone) continue
            Chip(kind.label, primary, filled = kind == action.kind, modifier = Modifier.clickable { onChange(action.copy(kind = kind)) })
        }
    }
    if (!action.kind.needsDeck) return
    Text("Stapel", Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val chosen = vm.deckOf(action)?.id
        Chip("offener Stapel / fragen", primary, filled = chosen == null, modifier = Modifier.clickable { onChange(action.copy(deckId = null)) })
        for (kind in DeckKind.entries) {
            for (deck in vm.decks(kind)) {
                Chip(deck.name, kindColor(kind), filled = deck.id == chosen, modifier = Modifier.clickable { onChange(action.copy(deckId = deck.id)) })
            }
        }
    }
}

/** The deck for an action that has none and was started outside a deck. */
@Composable
fun DeckPickDialog(vm: AppViewModel, pick: DeckPick) {
    AlertDialog(
        onDismissRequest = { vm.deckPick = null },
        title = { Text("${pick.kind.label}: welcher Stapel?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                for (kind in DeckKind.entries) {
                    val decks = vm.decks(kind)
                    if (decks.isEmpty()) continue
                    item {
                        Text(
                            kindName(kind),
                            Modifier.padding(top = 10.dp, bottom = 4.dp),
                            fontWeight = FontWeight.SemiBold,
                            color = kindColor(kind),
                        )
                    }
                    items(decks, key = { it.id }) { deck ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.pickDeck(deck.id) }.padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(deck.name, Modifier.weight(1f))
                            val due = vm.counts(deck).total
                            if (due > 0) Chip("$due", kindColor(kind))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ vm.deckPick = null }) { Text("Abbrechen") } },
    )
}

/** One shortcut: its key and what it does. */
@Composable
fun ShortcutDialog(vm: AppViewModel, edit: ShortcutEdit) {
    val old = vm.shortcuts.firstOrNull { it.key == edit.key }
    var key by remember(edit) { mutableStateOf(old?.key?.toString() ?: "") }
    var action by remember(edit) { mutableStateOf(old?.action ?: Action(ActionKind.STUDY)) }
    val k = key.singleOrNull()
    val problem = when {
        k == null -> "Eine Taste: Buchstabe oder ? – die Ziffern wählen die Stapel"
        else -> vm.shortcuts.firstOrNull { it.key == k && it.key != edit.key }?.let { "Ersetzt: ${vm.describe(it.action)}" }
    }
    AlertDialog(
        onDismissRequest = { vm.shortcutEdit = null },
        title = { Text(if (old == null) "Neues Tastenkürzel" else "Tastenkürzel ändern") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.settings.prefix?.let(vm::keyLabel) ?: "Prefix", fontWeight = FontWeight.SemiBold)
                    Text("  dann  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        key,
                        { key = it.filter(Shortcuts::bindable).takeLast(1).uppercase() },
                        Modifier.widthIn(max = 90.dp),
                        singleLine = true,
                    )
                }
                if (problem != null) {
                    Text(problem, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                ActionPicker(vm, action, allowNone = false) { action = it }
            }
        },
        confirmButton = {
            TextButton({
                vm.saveShortcut(edit.key, Shortcut(k!!, action))
                vm.shortcutEdit = null
            }, enabled = k != null) { Text("Speichern") }
        },
        dismissButton = { TextButton({ vm.shortcutEdit = null }) { Text("Abbrechen") } },
    )
}

/**
 * After the prefix: every key and what it does, like which-key in Emacs. A click works too.
 * While the settings wait for a new prefix, says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyOverlay(vm: AppViewModel) {
    if (vm.capturingPrefix) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { vm.capturingPrefix = false }, contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Neuen Prefix drücken", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (vm.platform.macKeys) "z. B. ⌃B, ⌥Leertaste oder F5 · Esc bricht ab" else "z. B. Strg+B, Alt+Leertaste oder F5 · Esc bricht ab", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    if (!vm.prefixPending) return
    val prefix = vm.settings.prefix ?: return
    Box(Modifier.fillMaxSize().clickable(onClickLabel = "Schließen") { vm.closePrefix() }, contentAlignment = Alignment.BottomCenter) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().clickable(enabled = false) {},
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                val on = MaterialTheme.colorScheme.inverseOnSurface
                Text("${vm.keyLabel(prefix)} …", color = on, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in vm.shortcuts) KeyEntry("${s.key}", vm.describe(s.action), Palette.cards, on) { vm.runShortcut(s.key) }
                    // The numbers stand in front of the decks in both lists.
                    val count = vm.numberedDecks().size.coerceAtMost(9)
                    if (count > 0) KeyEntry(if (count == 1) "1" else "1–$count", "Stapel wählen", Palette.chords, on) { vm.closePrefix() }
                    if (count > 0) KeyEntry("⇧1–$count", "Stapel markieren", Palette.chords, on) { vm.closePrefix() }
                }
                Text("Esc schließt", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun KeyEntry(key: String, label: String, color: Color, on: Color, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.heightIn(min = 26.dp).widthIn(min = 26.dp).clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(key, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
