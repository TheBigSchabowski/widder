package widder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The two paths each have their colour: amber for flash cards, teal for chord training. */
object Palette {
    val cards = Color(0xFFF2A541)
    val cardsDark = Color(0xFFB8741A)
    val chords = Color(0xFF3FB8A6)
    val chordsDark = Color(0xFF1F8A7A)
    val again = Color(0xFFE5484D)
    val hard = Color(0xFFE8963D)
    val good = Color(0xFF46A758)
    val easy = Color(0xFF3E8ED0)
    val newCount = Color(0xFF3E8ED0)
    val learnCount = Color(0xFFE5484D)
    val reviewCount = Color(0xFF46A758)
}

private val Dark = darkColorScheme(
    primary = Palette.cards,
    onPrimary = Color(0xFF231605),
    secondary = Palette.chords,
    onSecondary = Color(0xFF03201C),
    background = Color(0xFF16141A),
    onBackground = Color(0xFFECE7F0),
    surface = Color(0xFF211E26),
    onSurface = Color(0xFFECE7F0),
    surfaceVariant = Color(0xFF2D2933),
    onSurfaceVariant = Color(0xFFBDB6C6),
    surfaceContainer = Color(0xFF262229),
    surfaceContainerHigh = Color(0xFF2D2933),
    surfaceContainerHighest = Color(0xFF36313D),
    outline = Color(0xFF5B5563),
    outlineVariant = Color(0xFF3E3945),
    error = Palette.again,
)

private val Light = lightColorScheme(
    primary = Palette.cardsDark,
    onPrimary = Color.White,
    secondary = Palette.chordsDark,
    onSecondary = Color.White,
    background = Color(0xFFF8F5F0),
    onBackground = Color(0xFF1E1B20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1B20),
    surfaceVariant = Color(0xFFEFEAE3),
    onSurfaceVariant = Color(0xFF5A5460),
    surfaceContainer = Color(0xFFF3EFE9),
    surfaceContainerHigh = Color(0xFFEDE8E1),
    surfaceContainerHighest = Color(0xFFE6E0D8),
    outline = Color(0xFFB3ABB8),
    outlineVariant = Color(0xFFDCD5DF),
    error = Color(0xFFC62E33),
)

@Composable
fun WidderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
