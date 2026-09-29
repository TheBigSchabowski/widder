package widder.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes a PNG/JPEG/WebP; null if it is not an image. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Builds an image from ARGB pixels (row by row). */
expect fun imageFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap
