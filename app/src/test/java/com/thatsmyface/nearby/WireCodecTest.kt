package com.thatsmyface.nearby

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCodecTest {
    private val event = "event-12345678"
    private val peer = "person-12345678"
    private val photo = "photo-12345678"
    private val request = "request-12345678"
    private val checksum = "a".repeat(64)

    @Test fun validMessagesRoundTrip() {
        val messages = listOf(
            WireMessage.Hello(event, peer, "Friend", checksum),
            WireMessage.EventReady(event),
            WireMessage.Catalog(event, listOf(offer())),
            WireMessage.Catalog(event, emptyList(), reset = true),
            WireMessage.FaceReferences(event, "sface", listOf(listOf(1f) + List(127) { 0f })),
            WireMessage.Request(event, request, photo),
            WireMessage.Cancel(event, request, photo),
            WireMessage.Decision(event, request, photo, false, "Declined"),
            WireMessage.FileReady(event, request, photo, Long.MIN_VALUE, "shot.jpg", "image/jpeg", 123, checksum),
            WireMessage.Ready(event, request, Long.MIN_VALUE),
            WireMessage.Receipt(event, request, photo, checksum),
            WireMessage.MatchDecision(event, photo, "REJECTED"),
            WireMessage.Revoke(event, true),
            WireMessage.Error(event, request, "unavailable"),
        )
        messages.forEach { assertEquals(it, WireCodec.decode(WireCodec.encode(it), event)) }
    }

    @Test fun anotherEventCannotReadAMessage() {
        assertThrows(IllegalArgumentException::class.java) {
            WireCodec.decode(WireCodec.encode(WireMessage.Request(event, request, photo)), "other-event-123")
        }
    }

    @Test fun catalogResetAndCancellationRemainEventScoped() {
        listOf(WireMessage.Catalog(event, emptyList(), reset = true), WireMessage.Cancel(event, request, photo)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                WireCodec.decode(WireCodec.encode(it), "another-event")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireCodec.encode(WireMessage.Cancel(event, request, "../photo.jpg"))
        }
    }

    @Test fun pathsAreNeverPhotoIdentifiers() {
        listOf("../private.jpg", "/storage/photo", "content://photo", "C:\\photo.jpg").forEach { badId ->
            assertThrows(IllegalArgumentException::class.java) {
                WireCodec.encode(WireMessage.Request(event, request, badId))
            }
        }
    }

    @Test fun unsafeFilenameAndWrongMimeAreRejected() {
        listOf(offer().copy(fileName = "../photo.jpg"), offer().copy(mimeType = "application/octet-stream"), offer().copy(fileName = "a\u0000.jpg")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(WireMessage.Catalog(event, listOf(bad))) }
        }
    }

    @Test fun oversizedAndEmptyMessagesAreRejected() {
        listOf(ByteArray(0), ByteArray(WireCodec.MAX_MESSAGE_BYTES + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { WireCodec.decode(it, event) }
        }
    }

    @Test fun unknownMessageAndExtraFieldAreRejected() {
        listOf(
            """{"type":"delete_source","eventId":"$event"}""",
            """{"type":"request","eventId":"$event","requestId":"$request","photoId":"$photo","path":"secret"}""",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { WireCodec.decode(it.encodeToByteArray(), event) }
        }
    }

    @Test fun invalidUtf8IsRejectedBeforeParsing() {
        assertThrows(Exception::class.java) { WireCodec.decode(byteArrayOf(0xc3.toByte(), 0x28), event) }
    }

    @Test fun malformedAndUnboundedEmbeddingsAreRejected() {
        listOf(List(128) { 0f }, List(128) { Float.NaN }, List(128) { Float.POSITIVE_INFINITY }, listOf(1f), List(513) { 0.04f }).forEach { vector ->
            assertThrows(IllegalArgumentException::class.java) {
                WireCodec.encode(WireMessage.FaceReferences(event, "sface", listOf(vector)))
            }
        }
    }

    @Test fun tooManyReferencesAreRejected() {
        val vector = listOf(1f) + List(127) { 0f }
        assertThrows(IllegalArgumentException::class.java) {
            WireCodec.encode(WireMessage.FaceReferences(event, "sface", List(6) { vector }))
        }
    }

    @Test fun repeatedPhotoAndLargeCatalogAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(WireMessage.Catalog(event, listOf(offer(), offer()))) }
        assertThrows(IllegalArgumentException::class.java) {
            WireCodec.encode(WireMessage.Catalog(event, List(21) { offer().copy(photoId = "photo-12345678-$it") }))
        }
    }

    @Test fun previewBytesAreBoundedAndMustBeBase64() {
        listOf("not base 64!", "A".repeat(24 * 1024 + 1), "").forEach {
            assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(WireMessage.Catalog(event, listOf(offer().copy(previewBase64 = it)))) }
        }
        val preview = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val message = WireMessage.Catalog(event, listOf(offer().copy(previewBase64 = preview)))
        assertEquals(message, WireCodec.decode(WireCodec.encode(message), event))
    }

    @Test fun invalidFileSizeAndChecksumAreRejected() {
        val valid = WireMessage.FileReady(event, request, photo, 5, "shot.jpg", "image/jpeg", 123, checksum)
        listOf(valid.copy(byteCount = 0), valid.copy(byteCount = WireCodec.MAX_PHOTO_BYTES + 1), valid.copy(sha256 = "1234")).forEach {
            assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(it) }
        }
    }

    @Test fun rawSimilarityMustBeFiniteAndWithinCosineRange() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.1f, -1.1f).forEach {
            assertThrows(IllegalArgumentException::class.java) { WireCodec.encode(WireMessage.Catalog(event, listOf(offer().copy(similarity = it)))) }
        }
    }

    @Test fun invitationProofBindsIdentityEventAndCurrentConnection() {
        val secret = "a".repeat(64)
        val hello = WireMessage.Hello(event, peer, "Friend", InvitationProof.create(secret, event, peer, "Friend", "1234"))
        assertTrue(InvitationProof.verify(secret, hello, "1234"))
        assertFalse(InvitationProof.verify("b".repeat(64), hello, "1234"))
        assertFalse(InvitationProof.verify(secret, hello, "5678"))
        assertFalse(InvitationProof.verify(secret, hello.copy(peerId = "another-person"), "1234"))
        assertFalse(InvitationProof.verify(secret, hello.copy(eventId = "another-event"), "1234"))
        assertFalse(InvitationProof.verify(secret, hello.copy(nickname = "Impostor"), "1234"))
    }

    @Test fun shortInvitationSecretIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { InvitationProof.create("1234", event, peer, "Friend", "1234") }
    }

    private fun offer() = PhotoOffer(photo, "shot.jpg", "image/jpeg", 123)
}
