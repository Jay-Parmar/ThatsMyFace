package com.thatsmyface.nearby

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed class WireMessage {
    abstract val eventId: String

    @Serializable @SerialName("hello")
    data class Hello(override val eventId: String, val peerId: String, val nickname: String, val proof: String) : WireMessage()

    @Serializable @SerialName("catalog")
    data class Catalog(override val eventId: String, val photos: List<PhotoOffer>, val reset: Boolean = false) : WireMessage()

    @Serializable @SerialName("face_references")
    data class FaceReferences(override val eventId: String, val modelId: String, val embeddings: List<List<Float>>) : WireMessage()

    @Serializable @SerialName("request")
    data class Request(override val eventId: String, val requestId: String, val photoId: String) : WireMessage()

    @Serializable @SerialName("cancel")
    data class Cancel(override val eventId: String, val requestId: String, val photoId: String) : WireMessage()

    @Serializable @SerialName("decision")
    data class Decision(override val eventId: String, val requestId: String, val photoId: String, val approved: Boolean, val reason: String = "") : WireMessage()

    @Serializable @SerialName("file_ready")
    data class FileReady(
        override val eventId: String,
        val requestId: String,
        val photoId: String,
        val payloadId: Long,
        val fileName: String,
        val mimeType: String,
        val byteCount: Long,
        val sha256: String,
    ) : WireMessage()

    @Serializable @SerialName("ready")
    data class Ready(override val eventId: String, val requestId: String, val payloadId: Long) : WireMessage()

    @Serializable @SerialName("receipt")
    data class Receipt(override val eventId: String, val requestId: String, val photoId: String, val sha256: String) : WireMessage()

    @Serializable @SerialName("match_decision")
    data class MatchDecision(override val eventId: String, val photoId: String, val decision: String) : WireMessage()

    @Serializable @SerialName("revoke")
    data class Revoke(override val eventId: String, val faceDataOnly: Boolean) : WireMessage()

    @Serializable @SerialName("error")
    data class Error(override val eventId: String, val requestId: String? = null, val code: String) : WireMessage()
}

@Serializable
data class PhotoOffer(
    val photoId: String,
    val fileName: String,
    val mimeType: String,
    val byteCount: Long,
    val previewBase64: String? = null,
    val matchState: String = "NONE",
    val similarity: Float? = null,
)

object WireCodec {
    const val MAX_MESSAGE_BYTES = 28 * 1024
    const val MAX_PHOTO_BYTES = 100L * 1024 * 1024
    private val idPattern = Regex("[A-Za-z0-9_-]{8,64}")
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = false; isLenient = false }

    fun encode(message: WireMessage): ByteArray {
        validate(message)
        return json.encodeToString(message).encodeToByteArray().also {
            require(it.size <= MAX_MESSAGE_BYTES) { "Message is too large" }
        }
    }

    fun decode(bytes: ByteArray, expectedEventId: String): WireMessage {
        require(bytes.size in 1..MAX_MESSAGE_BYTES) { "Message is too large or empty" }
        return json.decodeFromString<WireMessage>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
            require(it.eventId == expectedEventId) { "Wrong event" }
            validate(it)
        }
    }

    fun validate(message: WireMessage) {
        requireId(message.eventId)
        when (message) {
            is WireMessage.Hello -> {
                requireId(message.peerId)
                requireText(message.nickname, 40, false)
                require(digestPattern.matches(message.proof)) { "Invalid event proof" }
            }
            is WireMessage.Catalog -> {
                require(message.photos.size <= 20) { "Catalog page is too large" }
                require(message.photos.map { it.photoId }.distinct().size == message.photos.size) { "Repeated photo ID" }
                message.photos.forEach { photo ->
                    requireId(photo.photoId)
                    requirePhotoMetadata(photo.fileName, photo.mimeType, photo.byteCount)
                    require(photo.matchState in setOf("NONE", "SUGGESTED", "UNCERTAIN", "MANUAL")) { "Invalid match state" }
                    photo.similarity?.let { require(it.isFinite() && it in -1f..1f) { "Invalid similarity" } }
                    photo.previewBase64?.let {
                        require(it.length <= 24 * 1024) { "Preview is too large" }
                        require(Base64.getDecoder().decode(it).isNotEmpty()) { "Empty preview" }
                    }
                }
            }
            is WireMessage.FaceReferences -> {
                requireText(message.modelId, 96, false)
                require(message.embeddings.size in 1..5) { "Choose one to five references" }
                message.embeddings.forEach { vector ->
                    require(vector.size in setOf(128, 512)) { "Unsupported embedding size" }
                    require(vector.all { it.isFinite() && it in -1f..1f }) { "Invalid reference" }
                    val norm = vector.sumOf { it.toDouble() * it }
                    require(norm in 0.95..1.05) { "Reference must be normalized" }
                }
                require(message.embeddings.map { it.size }.distinct().size == 1) { "Inconsistent references" }
            }
            is WireMessage.Request -> requireIds(message.requestId, message.photoId)
            is WireMessage.Cancel -> requireIds(message.requestId, message.photoId)
            is WireMessage.Ready -> requireId(message.requestId)
            is WireMessage.Decision -> {
                requireIds(message.requestId, message.photoId)
                requireText(message.reason, 160)
            }
            is WireMessage.FileReady -> {
                requireIds(message.requestId, message.photoId)
                requirePhotoMetadata(message.fileName, message.mimeType, message.byteCount)
                require(digestPattern.matches(message.sha256)) { "Invalid checksum" }
            }
            is WireMessage.Receipt -> {
                requireIds(message.requestId, message.photoId)
                require(digestPattern.matches(message.sha256)) { "Invalid checksum" }
            }
            is WireMessage.MatchDecision -> {
                requireId(message.photoId)
                require(message.decision in setOf("CONFIRMED", "REJECTED", "MANUAL")) { "Invalid decision" }
            }
            is WireMessage.Revoke -> Unit
            is WireMessage.Error -> {
                message.requestId?.let(::requireId)
                requireText(message.code, 80, false)
            }
        }
    }

    fun requireId(value: String) {
        require(idPattern.matches(value)) { "Invalid scoped identifier" }
    }

    private fun requireIds(vararg values: String) = values.forEach(::requireId)

    private fun requireText(value: String, maximum: Int, allowEmpty: Boolean = true) {
        require(value.length <= maximum && (allowEmpty || value.isNotBlank()) && value.none { it.isISOControl() }) { "Invalid text" }
    }

    private fun requirePhotoMetadata(name: String, mime: String, bytes: Long) {
        requireText(name, 120, false)
        require(name.none { it == '/' || it == '\\' } && name != "." && name != "..") { "Invalid filename" }
        require(mime in setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif")) { "Unsupported photo type" }
        require(bytes in 1..MAX_PHOTO_BYTES) { "Photo must be smaller than 100 MiB" }
    }
}

object InvitationProof {
    fun create(secret: String, eventId: String, peerId: String, nickname: String, authenticationDigits: String): String {
        require(secret.length in 32..256) { "Invalid invitation secret" }
        WireCodec.requireId(eventId)
        WireCodec.requireId(peerId)
        require(authenticationDigits.isNotBlank()) { "Verify this connection first" }
        require(nickname.none { it == '\n' })
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal("thatsmyface-v1\n$eventId\n$peerId\n$nickname\n$authenticationDigits".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun verify(secret: String, hello: WireMessage.Hello, authenticationDigits: String): Boolean =
        runCatching {
            val expected = create(secret, hello.eventId, hello.peerId, hello.nickname, authenticationDigits)
            MessageDigest.isEqual(expected.encodeToByteArray(), hello.proof.encodeToByteArray())
        }.getOrDefault(false)
}
