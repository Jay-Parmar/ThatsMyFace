package com.thatsmyface.data

import java.util.UUID
import kotlinx.serialization.Serializable

fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class AppState(
    val schemaVersion: Int = 1,
    val profile: Profile? = null,
    val events: List<Event> = emptyList(),
    val peers: List<Peer> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val offers: List<PhotoOffer> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val feedback: List<Feedback> = emptyList(),
)

@Serializable
data class Profile(
    val id: String = newId(),
    val nickname: String,
    val faceRefs: List<List<Float>> = emptyList(),
)

@Serializable
data class Event(
    val id: String = newId(),
    val title: String,
    val secret: String,
    val folderUri: String? = null,
    val shareFaceData: Boolean = false,
)

@Serializable
data class Peer(
    val eventId: String,
    val peerId: String,
    val nickname: String,
    val allowed: Boolean = true,
    val faceRefs: List<List<Float>> = emptyList(),
)

@Serializable
data class Photo(
    val eventId: String,
    val id: String = newId(),
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sha256: String,
    val size: Long,
    val manualPersonIds: List<String> = emptyList(),
    val availability: PhotoAvailability = PhotoAvailability.AVAILABLE,
    val error: String? = null,
)

@Serializable
enum class PhotoAvailability { AVAILABLE, PERMISSION_REVOKED, MISSING, CHANGED, UNREADABLE }

@Serializable
enum class MatchKind { AVAILABLE, MANUAL, SUGGESTED, UNCERTAIN }

@Serializable
enum class MatchDecision { NONE, CONFIRMED, REJECTED }

@Serializable
data class PhotoOffer(
    val eventId: String,
    val ownerId: String,
    val photoId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val sha256: String = "",
    val thumbnailBase64: String? = null,
    val match: MatchKind = MatchKind.AVAILABLE,
    val decision: MatchDecision = MatchDecision.NONE,
) {
    val key: String get() = "$eventId:$ownerId:$photoId"
}

@Serializable
enum class TransferDirection { SEND, RECEIVE }

@Serializable
enum class TransferStatus {
    WAITING, AWAITING_APPROVAL, QUEUED, TRANSFERRING, COMPLETE, FAILED, REJECTED, CANCELLED,
}

@Serializable
data class Transfer(
    val eventId: String,
    val ownerId: String,
    val photoId: String,
    val receiverId: String,
    val requestId: String = newId(),
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.WAITING,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val payloadId: Long? = null,
    val savedUri: String? = null,
    val bytesTransferred: Long = 0,
    val error: String? = null,
    val approved: Boolean = false,
) {
    val key: String get() = transferKey(eventId, ownerId, photoId, receiverId)
}

@Serializable
data class Feedback(
    val id: String = newId(),
    val note: String,
    val version: String,
    val createdAt: Long = System.currentTimeMillis(),
)
