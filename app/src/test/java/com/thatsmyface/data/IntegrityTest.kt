package com.thatsmyface.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IntegrityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun copyingPreservesEveryByteAndRejectsTruncationAndTampering() {
        val bytes = ByteArray(200_003) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val result = digest(bytes.inputStream(), output)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), result.size)
        val file = temporary.newFile().apply { writeBytes(bytes) }
        verifyIntegrity(file, result.size, result.sha256)
        file.writeBytes(bytes.dropLast(1).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { verifyIntegrity(file, result.size, result.sha256) }
        file.writeBytes(bytes.apply { this[0] = 22 })
        assertThrows(IllegalArgumentException::class.java) { verifyIntegrity(file, result.size, result.sha256) }
    }

    @Test fun rejectsInvalidMetadataAndOversizedStreams() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { verifyIntegrity(file, -1, "a".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { verifyIntegrity(file, 1, "../unsafe") }
        assertThrows(IllegalArgumentException::class.java) { digest(byteArrayOf().inputStream()) }
        val oversized = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
        }
        assertThrows(IllegalArgumentException::class.java) { digest(oversized) }
    }
}
