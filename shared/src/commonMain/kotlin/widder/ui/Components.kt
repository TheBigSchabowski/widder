package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Content never gets wider than a comfortable reading width; phones use the full width. */
@Composable
fun Centered(modifier: Modifier = Modifier, maxWidth: Dp = 760.dp, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(Glyph.BACK, "Zurück", onBack)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
    }
}

enum class Glyph { PLAY, STOP, RECORD, BACK, CLOSE, PLUS, REPLAY, MORE }

/** Simple drawn icons, so the app needs no icon library and looks the same everywhere. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.PLAY -> drawPath(
                Path().apply {
                    moveTo(w * 0.28f, h * 0.18f)
                    lineTo(w * 0.84f, h * 0.5f)
                    lineTo(w * 0.28f, h * 0.82f)
                    close()
                },
                color,
            )
            Glyph.STOP -> drawRect(color, Offset(w * 0.24f, h * 0.24f), Size(w * 0.52f, h * 0.52f))
            Glyph.RECORD -> drawCircle(color, w * 0.3f)
            Glyph.BACK -> {
                drawLine(color, Offset(w * 0.62f, h * 0.2f), Offset(w * 0.32f, h * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.5f), Offset(w * 0.62f, h * 0.8f), stroke.width, StrokeCap.Round)
            }
            Glyph.CLOSE -> {
                drawLine(color, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), stroke.width, StrokeCap.Round)
            }
            Glyph.PLUS -> {
                drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), stroke.width, StrokeCap.Round)
            }
            Glyph.REPLAY -> {
                drawArc(color, 40f, 290f, false, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.6f), style = stroke)
                drawPath(
                    Path().apply {
                        moveTo(w * 0.62f, h * 0.08f)
                        lineTo(w * 0.86f, h * 0.3f)
                        lineTo(w * 0.56f, h * 0.36f)
                        close()
                    },
                    color,
                )
            }
            Glyph.MORE -> for (i in 0..2) drawCircle(color, w * 0.08f, Offset(w * (0.25f + i * 0.25f), h * 0.5f))
        }
    }
}

@Composable
fun IconButton(glyph: Glyph, description: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) color else color.copy(alpha = 0.35f), 22.dp)
    }
}

/** Round play / stop button. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, color: Color, size: Dp = 48.dp, enabled: Boolean = true) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) color else color.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClickLabel = if (playing) "Stopp" else "Abspielen", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(if (playing) Glyph.STOP else Glyph.PLAY, Color.White, size * 0.5f)
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary) {
    TextButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (enabled) color else color.copy(alpha = 0.4f), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
    )
}

/** Anki's three numbers: new (blue), learning (red), due (green). */
@Composable
fun CountRow(new: Int, learning: Int, review: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CountBadge(new, Palette.newCount, "neu")
        CountBadge(learning, Palette.learnCount, "lernen")
        CountBadge(review, Palette.reviewCount, "fällig")
    }
}

@Composable
private fun CountBadge(n: Int, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$n",
            color = if (n > 0) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Chip(text: String, color: Color, filled: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = if (filled) Color.White else color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(height)).background(color))
    }
}

fun formatSeconds(s: Double): String {
    val tenths = (s * 100).roundToInt()
    return "${tenths / 100},${(tenths % 100).toString().padStart(2, '0')} s"
}

fun formatMinutes(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${(seconds + 59) / 60} Min"
    else -> "${seconds / 3600} Std ${(seconds % 3600) / 60} Min"
}
