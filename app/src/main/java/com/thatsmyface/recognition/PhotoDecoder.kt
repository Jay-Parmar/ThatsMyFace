package com.thatsmyface.recognition

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

object PhotoDecoder {
    const val MAX_DIMENSION = 1280

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid image dimensions" }
        val scale = minOf(1.0, MAX_DIMENSION.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    fun decode(resolver: ContentResolver, uri: Uri): Bitmap =
        decode(ImageDecoder.createSource(resolver, uri))

    internal fun decode(source: ImageDecoder.Source): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // ImageDecoder applies EXIF rotation and reflection before returning pixels.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val (width, height) = targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
        }
}
