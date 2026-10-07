package com.thatsmyface.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun actualModelsRecognizeAPortraitAfterJpegEncoding() = runBlocking {
        val portrait = fixture()
        val original = File(context.cacheDir, "recognition-original.png")
        val encoded = File(context.cacheDir, "recognition-encoded.jpg")
        try {
            save(portrait, original, Bitmap.CompressFormat.PNG)
            save(portrait, encoded, Bitmap.CompressFormat.JPEG)
            val engine = FaceEngine(context)
            val reference = engine.extract(Uri.fromFile(original)).single()
            val candidate = engine.extract(Uri.fromFile(encoded)).single()
            assertEquals(128, reference.embedding.size)
            assertTrue(reference.suitableForEnrollment)
            assertTrue(candidate.bounds.left < candidate.bounds.right)
            assertTrue(FaceMatcher.cosine(reference.embedding, candidate.embedding) > .9f)
            assertEquals(MatchKind.SUGGESTED, FaceMatcher.match(
                candidate.embedding, mapOf("test-participant" to listOf(reference.embedding)),
                candidate.issues.isEmpty(),
            ).kind)
        } finally {
            portrait.recycle()
            original.delete()
            encoded.delete()
        }
    }

    @Test fun detectorHandlesMultipleFacesAndAnEmptyImage() = runBlocking {
        val portrait = fixture()
        val pair = Bitmap.createBitmap(portrait.width * 2, portrait.height, Bitmap.Config.ARGB_8888)
        val blank = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val pairFile = File(context.cacheDir, "recognition-pair.png")
        val blankFile = File(context.cacheDir, "recognition-blank.png")
        try {
            Canvas(pair).apply {
                drawBitmap(portrait, 0f, 0f, null)
                drawBitmap(portrait, portrait.width.toFloat(), 0f, null)
            }
            save(pair, pairFile, Bitmap.CompressFormat.PNG)
            save(blank, blankFile, Bitmap.CompressFormat.PNG)
            val engine = FaceEngine(context)
            assertEquals(2, engine.extract(Uri.fromFile(pairFile)).size)
            assertTrue(engine.extract(Uri.fromFile(blankFile)).isEmpty())
        } finally {
            portrait.recycle()
            pair.recycle()
            blank.recycle()
            pairFile.delete()
            blankFile.delete()
        }
    }

    @Test fun imageDecoderAppliesAllEightExifOrientations() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        Canvas(source).apply {
            colors.forEachIndexed { i, color ->
                drawRect((i % 2) * 40f, (i / 2) * 20f, (i % 2 + 1) * 40f,
                    (i / 2 + 1) * 20f, Paint().apply { this.color = color })
            }
        }
        val expected = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(3, 2, 1, 0),
            intArrayOf(2, 3, 0, 1), intArrayOf(0, 2, 1, 3), intArrayOf(2, 0, 3, 1),
            intArrayOf(3, 1, 2, 0), intArrayOf(1, 3, 0, 2),
        )
        val file = File(context.cacheDir, "recognition-orientation.jpg")
        try {
            for (orientation in 1..8) {
                save(source, file, Bitmap.CompressFormat.JPEG)
                ExifInterface(file.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val decoded = PhotoDecoder.decode(ImageDecoder.createSource(file))
                try {
                    assertEquals(if (orientation <= 4) 80 else 40, decoded.width)
                    assertEquals(if (orientation <= 4) 40 else 80, decoded.height)
                    expected[orientation - 1].forEachIndexed { index, colorIndex ->
                        val actual = decoded.getPixel(
                            decoded.width * (if (index % 2 == 0) 1 else 3) / 4,
                            decoded.height * (if (index / 2 == 0) 1 else 3) / 4,
                        )
                        val wanted = colors[colorIndex]
                        assertTrue(kotlin.math.abs(Color.red(wanted) - Color.red(actual)) < 30)
                        assertTrue(kotlin.math.abs(Color.green(wanted) - Color.green(actual)) < 30)
                        assertTrue(kotlin.math.abs(Color.blue(wanted) - Color.blue(actual)) < 30)
                    }
                } finally {
                    decoded.recycle()
                }
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test fun missingMediaFailsInsteadOfReturningFakeMatches() = runBlocking {
        val missing = File(context.cacheDir, "missing-recognition-input.jpg")
        assertTrue(runCatching { FaceEngine(context).extract(Uri.fromFile(missing)) }.isFailure)
    }

    private fun fixture(): Bitmap = instrumentation.context.assets.open("astronaut.png").use {
        requireNotNull(BitmapFactory.decodeStream(it))
    }

    private fun save(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat) {
        file.outputStream().use { check(bitmap.compress(format, 92, it)) }
    }
}
