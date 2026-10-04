package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import widder.core.DeckKind
import widder.core.TileColor
import widder.core.TileScope
import widder.core.Reward
import widder.core.Store
import widder.core.levelFor
import widder.core.streak
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        RewardBackground(vm)
        if (vm.showRewardOnly) {
            Box(Modifier.fillMaxSize().clickable { vm.showRewardOnly = false }, contentAlignment = Alignment.BottomCenter) {
                Text(
                    "Tippen, um zurückzukehren",
                    Modifier.padding(24.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color.White,
                )
            }
            return
        }
        Centered {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
                Header(vm)
                Spacer(Modifier.height(18.dp))
                Paths(vm)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            "Doppelt tippen oder halten: Kacheln".takeIf { vm.settings.tileScope != TileScope.OFF },
                            vm.settings.prefix?.takeIf { vm.platform.keyboard }?.let { "${vm.keyLabel(it)} + Taste: Kürzel" },
                        ).joinToString(" · "),
                        Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBackdrop(vm).copy(alpha = 0.75f),
                    )
                    SmallButton("Kacheln anpassen", { vm.open(Screen.Tiles) }, color = onBackdrop(vm))
                }
                RewardStatus(vm)
                if (vm.platform.canDropFiles) {
                    Text(
                        "Audio- und MIDI-Dateien kannst du jederzeit ins Fenster ziehen.",
                        Modifier.padding(top = 14.dp, start = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBackdrop(vm).copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** Text colour over the background: white over a picture, theme colour otherwise. */
@Composable
private fun onBackdrop(vm: AppViewModel): Color =
    if (vm.rewardImages != null && vm.settings.reward.enabled) Color.White else MaterialTheme.colorScheme.onBackground

@Composable
private fun Header(vm: AppViewModel) {
    val color = onBackdrop(vm)
    val level = levelFor(vm.progress.totalXp)
    val streak = vm.progress.streak()
    val today = vm.progress.days[Store.learningDay()]
    Row(verticalAlignment = Alignment.CenterVertically) {
        RamMark(Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Widder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
            Text("Karteikarten, Gehör und Harmonielehre", style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.75f))
        }
        SmallButton("Einstellungen", { vm.open(Screen.Settings) }, color = color)
    }
    Spacer(Modifier.height(14.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Level ${level.level}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("${vm.progress.totalXp} XP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                ProgressBar(level.intoLevel.toFloat() / level.needed, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(18.dp))
            Stat("$streak", if (streak == 1) "Tag in Folge" else "Tage in Folge")
            Spacer(Modifier.width(14.dp))
            Stat("${today?.reviews ?: 0}", "heute")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Paths(vm: AppViewModel) {
    BoxWithConstraints {
        val cards: @Composable (Modifier) -> Unit = { m ->
            PathCard(
                m,
                color = Palette.cards,
                title = "Karteikarten",
                text = "Stapel wie in Anki, Audio aufnehmen, schneiden und mit Hall versehen, MIDI abspielen.",
                due = vm.dueTotal(DeckKind.CARDS),
                decks = vm.decks(DeckKind.CARDS).size,
                onClick = { vm.open(Screen.Decks(DeckKind.CARDS)) },
            )
        }
        val chords: @Composable (Modifier) -> Unit = { m ->
            PathCard(
                m,
                color = Palette.chords,
                title = "Akkord-Training",
                text = "Anhören und die Akkorde per Drag & Drop in die Reihenfolge ziehen, in der sie klingen.",
                due = vm.dueTotal(DeckKind.CHORDS),
                decks = vm.decks(DeckKind.CHORDS).size,
                onClick = { vm.open(Screen.Decks(DeckKind.CHORDS)) },
            )
        }
        if (maxWidth > 620.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                cards(Modifier.weight(1f))
                chords(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                cards(Modifier)
                chords(Modifier)
            }
        }
    }
}

@Composable
private fun PathCard(modifier: Modifier, color: Color, title: String, text: String, due: Int, decks: Int, onClick: () -> Unit) {
    Surface(
        modifier.fillMaxWidth().heightIn(min = 170.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Box {
            Box(Modifier.fillMaxWidth().height(6.dp).background(color))
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (due > 0) Chip("$due fällig", color, filled = true)
                }
                Spacer(Modifier.height(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (decks == 1) "1 Stapel  ›" else "$decks Stapel  ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

fun tileColor(color: TileColor): Color = when (color) {
    TileColor.AMBER -> Palette.cards
    TileColor.TEAL -> Palette.chords
    TileColor.RED -> Palette.again
    TileColor.BLUE -> Palette.easy
    TileColor.GREEN -> Palette.good
    TileColor.PURPLE -> Color(0xFF9B6DD6)
    TileColor.GREY -> Color(0xFF8A8494)
}

/** A key combination as a small key cap. */
@Composable
fun KeyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RewardStatus(vm: AppViewModel) {
    val settings = vm.settings.reward
    if (!settings.enabled) return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (vm.rewardImages == null) {
                Text("Bildbelohnung", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wähle ein Bild: Es liegt unscharf hinter diesem Bildschirm und wird mit jeder Lernminute Stück für Stück scharf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SmallButton("Bild wählen", vm::pickRewardImage)
                return@Column
            }
            val progress = vm.rewardProgress()
            val unlocked = Reward.unlocked(progress, settings)
            val total = Reward.tileCount(settings)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bildbelohnung: $unlocked von $total Teilen", fontWeight = FontWeight.SemiBold)
                    val next = Reward.secondsToNext(progress, settings)
                    Text(
                        if (next == null) "Komplett freigelernt!" else "Nächstes Teil nach ${formatMinutes(next)} Lernzeit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SmallButton("Bild ansehen", { vm.showRewardOnly = true })
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(progress.seconds.toFloat() / (total * settings.secondsPerTile), MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
        }
    }
}

/** The reward picture: blurred everywhere, sharp in the tiles already earned. */
@Composable
fun RewardBackground(vm: AppViewModel) {
    val images = vm.rewardImages
    val settings = vm.settings.reward
    val bg = MaterialTheme.colorScheme.background
    if (images == null || !settings.enabled) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Palette.cards.copy(alpha = 0.10f), bg, Palette.chords.copy(alpha = 0.10f))))
        }
        return
    }
    val sharp = Reward.sharpTiles(vm.rewardProgress(), settings)
    val scrim = !vm.showRewardOnly
    Canvas(Modifier.fillMaxSize()) {
        drawCropped(images.blurred)
        val tileW = size.width / settings.columns
        val tileH = size.height / settings.rows
        for (tile in sharp) {
            val x = tile % settings.columns
            val y = tile / settings.columns
            clipRect(x * tileW, y * tileH, (x + 1) * tileW, (y + 1) * tileH) { drawCropped(images.sharp) }
        }
        if (scrim) drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.55f))))
    }
}

/** Draws [image] filling the whole area, cut to its shape (like ContentScale.Crop). */
private fun DrawScope.drawCropped(image: ImageBitmap) {
    val scale = max(size.width / image.width, size.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}

/** The app's mark: a ram's horn spiral in the two path colours. */
@Composable
fun RamMark(modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Palette.cards, size.minDimension / 2)
        val stroke = size.minDimension * 0.09f
        var r = size.minDimension * 0.34f
        var start = 180f
        repeat(3) {
            drawArc(
                Palette.chordsDark,
                start,
                200f,
                false,
                Offset(c.x - r, c.y - r),
                Size(2 * r, 2 * r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            start += 160f
            r *= 0.62f
        }
    }
}
