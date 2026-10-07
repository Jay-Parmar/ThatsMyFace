package com.thatsmyface.ui

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewImagesTest {
    @Test fun smallPreviewDecodesButMalformedPayloadFailsClosed() {
        assertNotNull(decodePreview(Base64.encodeToString(png(), Base64.NO_WRAP)))
        assertNull(decodePreview("not an image"))
        assertNull(decodePreview("A".repeat(24_577)))
    }

    @Test fun tinyCompressedImageWithHugeDeclaredDimensionsIsRejectedBeforeAllocation() {
        val bytes = png()
        ByteBuffer.wrap(bytes).apply {
            putInt(16, 70_000)
            putInt(20, 70_000)
            val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
            putInt(29, crc)
        }
        assertNull(decodePreview(Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
