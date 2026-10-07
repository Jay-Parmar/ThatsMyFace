package com.thatsmyface.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal fun decodePreview(encoded: String): ImageBitmap? = runCatching {
    require(encoded.length <= 24_576)
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    require(bytes.size <= 18_432)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    // Compressed size alone cannot prevent a peer from sending an image that expands too far.
    require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })?.asImageBitmap()
}.getOrNull()
