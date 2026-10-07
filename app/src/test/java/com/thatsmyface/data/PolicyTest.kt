package com.thatsmyface.data

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val event = Event(id = "event", title = "Pandal night", secret = "a".repeat(64))
    private val photo = Photo(eventId = "event", id = "photo", uri = "content://photos/1", displayName = "photo.jpg",
        mimeType = "image/jpeg", sha256 = "b".repeat(64), size = 24)
    private val state = AppState(profile = Profile(id = "owner", nickname = "Owner"), events = listOf(event),
        peers = listOf(Peer("event", "friend", "Friend")), photos = listOf(photo))
    private val transfer = Transfer("event", "owner", "photo", "friend", direction = TransferDirection.SEND,
        status = TransferStatus.QUEUED, displayName = "photo.jpg", mimeType = "image/jpeg", size = 24, sha256 = "b".repeat(64))

    @Test fun membershipDoesNotLeakBetweenEvents() {
        assertTrue(state.canAccessPhoto("event", "friend", "photo"))
        assertFalse(state.canAccessPhoto("other", "friend", "photo"))
        assertFalse(state.canAccessPhoto("event", "stranger", "photo"))
        assertFalse(state.canAccessPhoto("event", "friend", "../photo"))
        assertFalse(state.copy(events = emptyList()).canAccessPhoto("event", "friend", "photo"))
    }

    @Test fun matchCannotReplaceOwnerApproval() {
        val offer = PhotoOffer("event", "owner", "photo", "photo.jpg", "image/jpeg", 24,
            match = MatchKind.SUGGESTED, decision = MatchDecision.CONFIRMED)
        val confirmed = state.copy(offers = listOf(offer))
        assertFalse(confirmed.canSendOriginal(transfer))
        assertTrue(confirmed.canSendOriginal(transfer.copy(approved = true)))
        assertFalse(confirmed.canSendOriginal(transfer.copy(approved = true, ownerId = "other")))
        assertFalse(confirmed.canSendOriginal(transfer.copy(approved = true, sha256 = "c".repeat(64))))
        assertFalse(confirmed.canSendOriginal(transfer.copy(approved = true, status = TransferStatus.CANCELLED)))
    }

    @Test fun removedOrMissingPhotosCannotBeShared() {
        assertFalse(state.copy(photos = listOf(photo.copy(availability = PhotoAvailability.PERMISSION_REVOKED)))
            .canSendOriginal(transfer.copy(approved = true)))
        assertFalse(state.copy(photos = emptyList()).canSendOriginal(transfer.copy(approved = true)))
        assertFalse(state.revokePeer("event", "friend").canSendOriginal(transfer.copy(approved = true)))
    }

    @Test fun matchRejectionAndCorrectionKeepAccessUnchanged() {
        val offer = PhotoOffer("event", "owner", "photo", "photo.jpg", "image/jpeg", 24, match = MatchKind.UNCERTAIN)
        val initial = state.copy(offers = listOf(offer))
        val rejected = initial.decideMatch(offer.key, MatchDecision.REJECTED)
        assertEquals(MatchDecision.REJECTED, rejected.offers.single().decision)
        assertEquals(MatchDecision.CONFIRMED, rejected.decideMatch(offer.key, MatchDecision.CONFIRMED).offers.single().decision)
        assertFalse(rejected.canSendOriginal(transfer))
        val corrected = rejected.tagPhoto("event", "photo", listOf("friend", "friend"))
        assertEquals(listOf("friend"), corrected.photos.single().manualPersonIds)
        assertThrows(IllegalArgumentException::class.java) { corrected.tagPhoto("event", "photo", listOf("stranger")) }
    }

    @Test fun completeTransferSurvivesDuplicateRequestsAndRestart() {
        val completed = transfer.copy(status = TransferStatus.COMPLETE, savedUri = "content://received/1", approved = true)
        val saved = state.upsertTransfer(completed)
        assertEquals(listOf(completed), saved.upsertTransfer(transfer.copy(requestId = "new-request")).transfers)
        assertEquals(completed, saved.recoverTransfers().transfers.single())
        assertNotEquals(transfer.key, transfer.copy(eventId = "another-event").key)
        assertNotEquals(transferKey("a:b", "c", "d", "e"), transferKey("a", "b:c", "d", "e"))
    }

    @Test fun interruptedTransfersWaitWithoutLosingApprovalOrChangingRequestIdentity() {
        val running = transfer.copy(status = TransferStatus.TRANSFERRING, payloadId = 123, bytesTransferred = 10, approved = true)
        val restored = state.upsertTransfer(running).recoverTransfers().transfers.single()
        assertEquals(TransferStatus.WAITING, restored.status)
        assertEquals(running.requestId, restored.requestId)
        assertTrue(restored.approved)
        assertEquals(0L, restored.bytesTransferred)
        assertNull(restored.payloadId)
        assertFalse(state.canSendOriginal(restored))
    }

    @Test fun approvalCanFillUnknownHashButCannotReplaceKnownOriginal() {
        val requested = transfer.copy(sha256 = "", status = TransferStatus.AWAITING_APPROVAL)
        val ready = state.upsertTransfer(requested).upsertTransfer(transfer)
        assertEquals(transfer.sha256, ready.transfers.single().sha256)
        assertEquals(transfer.sha256, ready.upsertTransfer(requested).transfers.single().sha256)
        assertThrows(IllegalArgumentException::class.java) { ready.upsertTransfer(transfer.copy(sha256 = "c".repeat(64))) }
    }

    @Test fun restartDropsPeerReferencesAndWaitsForDisconnectedApprovalRequests() {
        val original = state.copy(peers = listOf(state.peers.single().copy(faceRefs = listOf(listOf(0.5f, 0.5f)))),
            transfers = listOf(transfer.copy(status = TransferStatus.AWAITING_APPROVAL, approved = false)))
        val recovered = original.recoverTransfers()
        assertTrue(recovered.peers.single().faceRefs.isEmpty())
        assertEquals(TransferStatus.WAITING, recovered.transfers.single().status)
        assertFalse(recovered.transfers.single().approved)
        assertEquals(original.transfers.single().requestId, recovered.transfers.single().requestId)
    }

    @Test fun deletingFaceDataRemovesReferencesAndConsentButPreservesManualTags() {
        val faceState = state.copy(profile = state.profile!!.copy(faceRefs = listOf(listOf(0.1f, 0.2f))),
            events = listOf(event.copy(shareFaceData = true)),
            peers = listOf(state.peers.single().copy(faceRefs = listOf(listOf(0.3f, 0.4f)))),
            photos = listOf(photo.copy(manualPersonIds = listOf("friend"))),
            offers = listOf(PhotoOffer("event", "friend", "other", "other.jpg", "image/jpeg", 100, match = MatchKind.SUGGESTED)))
        val cleared = faceState.withoutFaceData()
        assertTrue(cleared.profile!!.faceRefs.isEmpty())
        assertTrue(cleared.peers.single().faceRefs.isEmpty())
        assertFalse(cleared.events.single().shareFaceData)
        assertTrue(cleared.offers.isEmpty())
        assertEquals(listOf("friend"), cleared.photos.single().manualPersonIds)
    }

    @Test fun revocationStopsFutureWorkAndKeepsDownloadedCopyHistory() {
        val complete = transfer.copy(photoId = "already-saved", status = TransferStatus.COMPLETE, savedUri = "content://saved/1")
        val revoked = state.copy(transfers = listOf(transfer, complete)).revokePeer("event", "friend")
        assertEquals(TransferStatus.CANCELLED, revoked.transfers.first().status)
        assertFalse(revoked.transfers.first().approved)
        assertEquals(complete, revoked.transfers.last())
    }
}
