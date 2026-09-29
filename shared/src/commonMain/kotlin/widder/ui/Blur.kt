package widder.ui

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The blur from the AnkiDroid fork: shrink the picture to a thumbnail, box-blur it three times
 * and let drawing scale it back up. Cheap, and the same on every platform and Android version.
 */
object Blur {
    fun blurred(image: ImageBitmap, strength: Int): ImageBitmap {
        val width = when (strength) {
            1 -> 220
            2 -> 150
            3 -> 100
            else -> 60
        }.coerceAtMost(image.width)
        val height = (image.height.toLong() * width / image.width).toInt().coerceAtLeast(1)
        val thumb = ImageBitmap(width, height)
        Canvas(thumb).drawImageRect(
            image,
            IntOffset.Zero,
            IntSize(image.width, image.height),
            IntOffset.Zero,
            IntSize(width, height),
            Paint().apply { filterQuality = FilterQuality.Medium },
        )
        val pixels = IntArray(width * height)
        thumb.readPixels(pixels, 0, 0, width, height)
        repeat(3) {
            boxBlur(pixels, width, height, horizontal = true)
            boxBlur(pixels, width, height, horizontal = false)
        }
        return imageFromArgb(pixels, width, height)
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, horizontal: Boolean, radius: Int = 2) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val line = IntArray(length)
        for (l in 0 until lines) {
            for (i in 0 until length) line[i] = pixels[if (horizontal) l * width + i else i * width + l]
            for (i in 0 until length) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (k in -radius..radius) {
                    val p = line[(i + k).coerceIn(0, length - 1)]
                    r += p shr 16 and 0xFF
                    g += p shr 8 and 0xFF
                    b += p and 0xFF
                    n++
                }
                pixels[if (horizontal) l * width + i else i * width + l] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
