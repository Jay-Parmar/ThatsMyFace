package com.thatsmyface.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

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

    @Test fun degradedPhotosCannotBecomeClearEnrollmentOrCertainMatches() = runBlocking {
        val portrait = fixture()
        val original = File(context.cacheDir, "recognition-quality-original.png")
        val candidateFile = File(context.cacheDir, "recognition-quality-candidate.png")
        val candidates = mutableListOf<Pair<Bitmap, FaceIssue>>()
        try {
            save(portrait, original, Bitmap.CompressFormat.PNG)
            val engine = FaceEngine(context)
            val reference = engine.extract(Uri.fromFile(original)).single()
            assertTrue(reference.suitableForEnrollment)
            candidates += Bitmap.createScaledBitmap(portrait, 256, 256, true) to FaceIssue.TOO_SMALL
            candidates += adjusted(portrait, .2f, 0f) to FaceIssue.DARK
            candidates += blurred(portrait) to FaceIssue.BLURRED
            for ((bitmap, expectedIssue) in candidates) {
                save(bitmap, candidateFile, Bitmap.CompressFormat.PNG)
                val faces = engine.extract(Uri.fromFile(candidateFile))
                assertTrue("The degraded fixture should still exercise quality assessment", faces.isNotEmpty())
                for (face in faces) {
                    assertTrue("Expected $expectedIssue", expectedIssue in face.issues)
                    assertFalse(face.suitableForEnrollment)
                    assertFalse(FaceMatcher.match(face.embedding,
                        mapOf("test-participant" to listOf(reference.embedding)), face.issues.isEmpty(),
                    ).kind == MatchKind.SUGGESTED)
                }
            }
            val washedOut = adjusted(portrait, .25f, 220f)
            try {
                save(washedOut, candidateFile, Bitmap.CompressFormat.PNG)
                for (face in engine.extract(Uri.fromFile(candidateFile))) {
                    assertFalse(face.suitableForEnrollment)
                    assertFalse(FaceMatcher.match(face.embedding,
                        mapOf("test-participant" to listOf(reference.embedding)), face.issues.isEmpty(),
                    ).kind == MatchKind.SUGGESTED)
                }
            } finally {
                washedOut.recycle()
            }
        } finally {
            candidates.forEach { it.first.recycle() }
            portrait.recycle()
            original.delete()
            candidateFile.delete()
        }
    }

    @Test fun actualRecognitionUsesCorrectedExifPixels() = runBlocking {
        val portrait = fixture()
        val original = File(context.cacheDir, "recognition-exif-original.jpg")
        val oriented = File(context.cacheDir, "recognition-exif-candidate.jpg")
        val inverseTransforms = listOf(
            Matrix().apply { setScale(-1f, 1f) },
            Matrix().apply { setRotate(180f) },
            Matrix().apply { setScale(1f, -1f) },
            Matrix().apply { setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)) },
            Matrix().apply { setRotate(270f) },
            Matrix().apply { setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)) },
            Matrix().apply { setRotate(90f) },
        )
        try {
            save(portrait, original, Bitmap.CompressFormat.JPEG)
            val engine = FaceEngine(context)
            val reference = engine.extract(Uri.fromFile(original)).single()
            inverseTransforms.forEachIndexed { index, transform ->
                val pixels = Bitmap.createBitmap(portrait, 0, 0, portrait.width, portrait.height, transform, true)
                try {
                    save(pixels, oriented, Bitmap.CompressFormat.JPEG)
                } finally {
                    pixels.recycle()
                }
                ExifInterface(oriented.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, (index + 2).toString())
                    saveAttributes()
                }
                val face = engine.extract(Uri.fromFile(oriented)).single()
                assertTrue("EXIF orientation ${index + 2} must be applied before inference",
                    FaceMatcher.cosine(reference.embedding, face.embedding) > .9f)
                assertTrue(face.bounds.left >= 0f && face.bounds.right <= 1f)
                assertTrue(face.bounds.top >= 0f && face.bounds.bottom <= 1f)
            }
        } finally {
            portrait.recycle()
            original.delete()
            oriented.delete()
        }
    }

    @Test fun resizeAndOrientationKeepTheDecodedImageWithinBudget() {
        val source = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "recognition-large-rotated.jpg")
        try {
            Canvas(source).apply {
                drawColor(Color.RED)
                drawRect(800f, 0f, 1600f, 800f, Paint().apply { color = Color.BLUE })
            }
            save(source, file, Bitmap.CompressFormat.JPEG)
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val decoded = PhotoDecoder.decode(ImageDecoder.createSource(file))
            try {
                assertEquals(640, decoded.width)
                assertEquals(1280, decoded.height)
                assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), decoded.colorSpace)
                assertFalse(decoded.config == Bitmap.Config.HARDWARE)
                assertTrue(Color.red(decoded.getPixel(320, 320)) > 225)
                assertTrue(Color.blue(decoded.getPixel(320, 960)) > 225)
            } finally {
                decoded.recycle()
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test fun corruptImageFailureDoesNotPoisonTheNextExtraction() = runBlocking {
        val portrait = fixture()
        val invalid = File(context.cacheDir, "recognition-corrupt.png")
        val valid = File(context.cacheDir, "recognition-recovery.png")
        try {
            invalid.writeBytes(byteArrayOf(0, 1, 2, 3))
            save(portrait, valid, Bitmap.CompressFormat.PNG)
            val engine = FaceEngine(context)
            assertTrue(runCatching { engine.extract(Uri.fromFile(invalid)) }.isFailure)
            assertTrue(engine.extract(Uri.fromFile(valid)).single().suitableForEnrollment)
        } finally {
            portrait.recycle()
            invalid.delete()
            valid.delete()
        }
    }

    private fun adjusted(source: Bitmap, scale: Float, offset: Float): Bitmap {
        val target = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ))
        Canvas(target).drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return target
    }

    private fun blurred(source: Bitmap): Bitmap {
        val pixels = Mat()
        val result = Mat()
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            Utils.bitmapToMat(source, pixels)
            Imgproc.GaussianBlur(pixels, result, Size(21.0, 21.0), 5.0)
            Utils.matToBitmap(result, bitmap)
            return bitmap
        } finally {
            pixels.release()
            result.release()
        }
    }

    private fun fixture(): Bitmap = instrumentation.context.assets.open("astronaut.png").use {
        requireNotNull(BitmapFactory.decodeStream(it))
    }

    private fun save(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat) {
        file.outputStream().use { check(bitmap.compress(format, 92, it)) }
    }
}
