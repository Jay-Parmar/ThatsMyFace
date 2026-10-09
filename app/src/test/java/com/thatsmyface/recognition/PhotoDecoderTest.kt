package com.thatsmyface.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDecoderTest {
    @Test fun largePhotosFitMemoryBudgetWithoutChangingAspectRatio() {
        val (width, height) = PhotoDecoder.targetSize(12000, 9000)
        assertEquals(1280, width)
        assertEquals(960, height)
    }

    @Test fun smallAndExtremeAspectRatiosRemainDecodable() {
        assertEquals(320 to 240, PhotoDecoder.targetSize(320, 240))
        assertEquals(1 to 1280, PhotoDecoder.targetSize(1, Int.MAX_VALUE))
        assertTrue(runCatching { PhotoDecoder.targetSize(0, 100) }.isFailure)
    }

    @Test fun portraitAndLandscapePhotosUseTheSameMemoryLimit() {
        for ((width, height) in listOf(4032 to 3024, 3001 to 1201, Int.MAX_VALUE to 1)) {
            val landscape = PhotoDecoder.targetSize(width, height)
            val portrait = PhotoDecoder.targetSize(height, width)
            assertEquals(landscape.first to landscape.second, portrait.second to portrait.first)
            assertEquals(1280, landscape.first)
            assertTrue(landscape.second in 1..1280)
        }
    }

    @Test fun invalidDimensionsAreRejectedBeforeDecode() {
        for ((width, height) in listOf(-1 to 100, 100 to -1, 100 to 0, 0 to 0, Int.MIN_VALUE to 1)) {
            assertTrue(runCatching { PhotoDecoder.targetSize(width, height) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
