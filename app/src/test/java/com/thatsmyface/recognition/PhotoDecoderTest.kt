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
}
