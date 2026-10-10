package widder.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** The field under [position] when [size] is split into [columns] × [rows], counted row by row. */
fun tileIndexAt(position: Offset, size: IntSize, columns: Int, rows: Int): Int {
    val column = (position.x / size.width * columns).toInt().coerceIn(0, columns - 1)
    val row = (position.y / size.height * rows).toInt().coerceIn(0, rows - 1)
    return row * columns + column
}

/**
 * The invisible grid over the whole app. It watches every touch before the screen below gets it
 * and steps in only for its own gestures: a tap reaches the screen as always; a long press on a
 * field with an action runs it and cancels the press below (a button there does not fire); the
 * second tap of a double tap is kept from the screen. The first tap of a double tap still reaches
 * the screen – that cannot be told apart from a single tap in time.
 */
fun Modifier.tileGestures(vm: AppViewModel): Modifier = pointerInput(vm) {
    var lastTapUp = 0L
    var lastTapAt = Offset.Zero
    var lastTapField = -1
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!vm.tilesActive()) return@awaitEachGesture
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val field = tileIndexAt(down.position, size, vm.gridColumns, vm.gridRows)

        val sinceTap = down.uptimeMillis - lastTapUp
        val secondTap = field == lastTapField &&
            sinceTap in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastTapAt).getDistance() < viewConfiguration.touchSlop * 4
        lastTapField = -1
        if (secondTap && vm.hasTileAction(field, long = false)) {
            down.consume()
            swallowUntilUp()
            vm.onTileGesture(field, long = false)
            return@awaitEachGesture
        }

        val waitLong = vm.hasTileAction(field, long = true)
        val end: PointerInputChange? = if (waitLong) {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitTapEnd(down.id, down.position) }
        } else {
            awaitTapEnd(down.id, down.position)
        }
        when {
            // Held long enough without moving: the field's long press.
            end == null && waitLong -> if (vm.onTileGesture(field, long = true)) swallowUntilUp()
            end != null && !end.pressed -> {
                lastTapUp = end.uptimeMillis
                lastTapAt = end.position
                lastTapField = field
            }
            // Moved (scrolling, dragging) or a second finger: not ours.
        }
    }
}

/** Waits for the finger to lift; returns the lift, or a still-pressed change when it moved away or another finger came. */
private suspend fun AwaitPointerEventScope.awaitTapEnd(id: PointerId, start: Offset): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id } ?: return null
        if (event.changes.size > 1) return change
        if (!change.pressed) return change
        if ((change.position - start).getDistance() > viewConfiguration.touchSlop) return change
    }
}

/** Keeps the rest of this touch from the screen below, so nothing there reacts to it. */
private suspend fun AwaitPointerEventScope.swallowUntilUp() {
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/** What the grid shows: faint outlines (if wanted) and a flash on the field whose gesture just fired. */
@Composable
fun TileLayer(vm: AppViewModel) {
    val flash = vm.tileFlash
    val outlines = vm.settings.showTiles && vm.tilesActive()
    if (flash == null && !outlines) return
    val columns = vm.gridColumns
    val rows = vm.gridRows
    val tiles = vm.tiles
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth / columns
        val h = maxHeight / rows
        fun Modifier.cell(index: Int) = offset(w * (index % columns), h * (index / columns)).size(w, h)
        if (outlines) {
            tiles.forEachIndexed { i, tile ->
                // Only what can do something here, e.g. no "back to the card" while learning.
                if (!vm.canRun(tile.doubleTap) && !vm.canRun(tile.longPress)) return@forEachIndexed
                val color = tileColor(tile.color)
                Box(Modifier.cell(i).padding(4.dp).border(1.5.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.BottomEnd) {
                    Text(
                        tile.displayTitle,
                        Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (flash != null) {
            val fade = remember(flash.serial) { Animatable(1f) }
            LaunchedEffect(flash.serial) {
                fade.animateTo(0f, tween(900))
                vm.clearTileFlash(flash.serial)
            }
            val color = tileColor(tiles.getOrNull(flash.index)?.color ?: widder.core.TileColor.GREY)
            Box(
                Modifier.cell(flash.index).alpha(fade.value).padding(4.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.22f))
                    .border(2.dp, color, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    flash.label,
                    Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
