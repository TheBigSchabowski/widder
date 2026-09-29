package widder.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import widder.ui.RamMark
import java.io.File

/**
 * Renders the app icon (the ram's horn on a dark rounded square, in the macOS icon grid) as a
 * 1024 px PNG; tools/make-icon.sh turns it into desktopApp/icon.icns.
 */
fun main() {
    val out = File("build/icon-1024.png")
    ImageComposeScene(1024, 1024, Density(1f)) {
        Box(Modifier.fillMaxSize().padding(100.dp).clip(RoundedCornerShape(185.dp)).background(Color(0xFF16141A)), contentAlignment = Alignment.Center) {
            RamMark(Modifier.fillMaxSize().padding(110.dp))
        }
    }.use { scene ->
        out.writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    println("Icon: ${out.absolutePath}")
    System.exit(0)
}
