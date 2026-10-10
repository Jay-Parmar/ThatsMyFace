package com.thatsmyface.data

import java.security.MessageDigest

const val MAX_PHOTO_BYTES: Long = 100L * 1024 * 1024

fun AppState.isApprovedPeer(eventId: String, peerId: String): Boolean =
    events.any { it.id == eventId } &&
        peers.any { it.eventId == eventId && it.peerId == peerId && it.allowed }

fun AppState.canAccessPhoto(eventId: String, peerId: String, photoId: String): Boolean =
    isApprovedPeer(eventId, peerId) && photos.any {
        it.eventId == eventId && it.id == photoId && it.availability == PhotoAvailability.AVAILABLE
    }

fun AppState.canSendOriginal(transfer: Transfer): Boolean =
    transfer.ownerId == profile?.id && transfer.direction == TransferDirection.SEND &&
        transfer.approved && transfer.status in setOf(TransferStatus.QUEUED, TransferStatus.TRANSFERRING) &&
        canAccessPhoto(transfer.eventId, transfer.receiverId, transfer.photoId) &&
        photos.any {
            it.eventId == transfer.eventId && it.id == transfer.photoId &&
                it.sha256 == transfer.sha256 && it.size == transfer.size
        }

fun transferKey(eventId: String, ownerId: String, photoId: String, receiverId: String): String {
    val bytes = listOf(eventId, ownerId, photoId, receiverId).joinToString(":") { "${it.length}:$it" }
    return sha256(bytes.toByteArray(Charsets.UTF_8))
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun AppState.recoverTransfers(): AppState = copy(peers = peers.map { it.copy(faceRefs = emptyList()) }, transfers = transfers.map {
    if (it.status in setOf(TransferStatus.AWAITING_APPROVAL, TransferStatus.QUEUED, TransferStatus.TRANSFERRING)) {
        it.copy(
            status = TransferStatus.WAITING,
            payloadId = null,
            bytesTransferred = 0,
            error = "Reconnect to your friend and retry.",
        )
    } else it.copy(payloadId = null)
})

fun AppState.withoutFaceData(): AppState = copy(
    profile = profile?.copy(faceRefs = emptyList()),
    events = events.map { it.copy(shareFaceData = false) },
    peers = peers.map { it.copy(faceRefs = emptyList()) },
    offers = offers.filter { it.match == MatchKind.MANUAL || it.match == MatchKind.AVAILABLE },
)

fun AppState.revokePeer(eventId: String, peerId: String): AppState = copy(
    peers = peers.map {
        if (it.eventId == eventId && it.peerId == peerId) it.copy(allowed = false, faceRefs = emptyList()) else it
    },
    offers = offers.filterNot { it.eventId == eventId && it.ownerId == peerId },
    transfers = transfers.map {
        if (it.eventId == eventId && (it.ownerId == peerId || it.receiverId == peerId) &&
            it.status != TransferStatus.COMPLETE
        ) it.copy(status = TransferStatus.CANCELLED, approved = false, payloadId = null, error = "Access removed.") else it
    },
)

fun AppState.decideMatch(offerKey: String, decision: MatchDecision): AppState = copy(
    offers = offers.map { if (it.key == offerKey) it.copy(decision = decision) else it },
)

fun AppState.tagPhoto(eventId: String, photoId: String, participantIds: List<String>): AppState {
    val allowedIds = peers.filter { it.eventId == eventId && it.allowed }.map { it.peerId }.toSet() + listOfNotNull(profile?.id)
    require(participantIds.all { it in allowedIds }) { "Only verified event friends can be tagged." }
    return copy(photos = photos.map {
        if (it.eventId == eventId && it.id == photoId) it.copy(manualPersonIds = participantIds.distinct()) else it
    })
}

fun AppState.upsertTransfer(transfer: Transfer): AppState {
    val existing = transfers.find { it.key == transfer.key }
    if (existing?.status == TransferStatus.COMPLETE) return this
    require(existing == null || existing.sha256.isEmpty() || transfer.sha256.isEmpty() || existing.sha256 == transfer.sha256) {
        "Photo changed. Import it again before sharing."
    }
    val next = if (existing != null && transfer.sha256.isEmpty()) transfer.copy(sha256 = existing.sha256) else transfer
    return copy(transfers = transfers.filterNot { it.key == transfer.key } + next)
}
