package com.thatsmyface.data

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class StateCodecTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val state = AppState(profile = Profile(nickname = "Private nickname", faceRefs = listOf(listOf(0.1f, 0.2f))))

    @Test fun encryptedStateRoundTripsWithoutPlaintextAndUsesFreshNonce() {
        val encrypted = StateCodec.encrypt(state, key)
        assertEquals(state, StateCodec.decrypt(encrypted, key))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("Private nickname"))
        assertFalse(encrypted.contentEquals(StateCodec.encrypt(state, key)))
    }

    @Test fun alteredCiphertextCannotBeLoaded() {
        val altered = StateCodec.encrypt(state, key)
        altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { StateCodec.decrypt(altered, key) }
    }

    @Test fun differentKeyCannotReadDataAndFutureSchemaIsRejected() {
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(AEADBadTagException::class.java) { StateCodec.decrypt(StateCodec.encrypt(state, key), otherKey) }
        assertThrows(IllegalArgumentException::class.java) { StateCodec.encrypt(state.copy(schemaVersion = 2), key) }
        val wrongHeader = StateCodec.encrypt(state, key).apply { this[3] = 2 }
        assertThrows(IllegalArgumentException::class.java) { StateCodec.decrypt(wrongHeader, key) }
    }

    @Test fun oldCompletedTransfersDefaultToUncheckedAndNewCopyChecksPersist() {
        val completed = Transfer("event", "owner", "photo", "receiver", direction = TransferDirection.RECEIVE,
            status = TransferStatus.COMPLETE, displayName = "photo.png", mimeType = "image/png", size = 12,
            sha256 = "a".repeat(64), savedUri = "content://saved/1", savedCopyAvailability = SavedCopyAvailability.MISSING)
        val oldRecord = JsonObject(Json.encodeToJsonElement(completed).jsonObject - "savedCopyAvailability")
        assertEquals(SavedCopyAvailability.UNCHECKED, Json.decodeFromJsonElement<Transfer>(oldRecord).savedCopyAvailability)
        val withCheck = state.copy(transfers = listOf(completed))
        assertEquals(withCheck, StateCodec.decrypt(StateCodec.encrypt(withCheck, key), key))
    }
}
