package com.thatsmyface

import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.data.Peer
import com.thatsmyface.data.Profile
import com.thatsmyface.data.Transfer
import com.thatsmyface.data.TransferDirection
import com.thatsmyface.data.TransferStatus
import com.thatsmyface.data.revokePeer
import com.thatsmyface.nearby.PayloadStatus
import com.thatsmyface.nearby.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {
    private val owner = "owner-12345678"
    private val receiver = "receiver-12345678"
    private val event = "event-12345678"
    private val photo = "photo-12345678"
    private val hash = "a".repeat(64)

    @Test fun aLateApprovalCannotReopenCancelledOrFailedAttempts() {
        listOf(TransferStatus.CANCELLED, TransferStatus.REJECTED, TransferStatus.FAILED, TransferStatus.COMPLETE, TransferStatus.TRANSFERRING).forEach { status ->
            val transfer = transfer().copy(direction = TransferDirection.RECEIVE, status = status)
            assertEquals(transfer, SessionPolicy.decision(transfer, true, ""))
        }
    }

    @Test fun approvalAppliesOnlyToRequestedIncomingAttempt() {
        val incoming = transfer().copy(direction = TransferDirection.RECEIVE, status = TransferStatus.AWAITING_APPROVAL, approved = false)
        val accepted = SessionPolicy.decision(incoming, true, "")
        assertEquals(TransferStatus.QUEUED, accepted.status)
        assertTrue(accepted.approved)
        val denied = SessionPolicy.decision(incoming, false, "Owner declined")
        assertEquals(TransferStatus.REJECTED, denied.status)
        assertFalse(denied.approved)
        val outgoing = incoming.copy(direction = TransferDirection.SEND)
        assertEquals(outgoing, SessionPolicy.decision(outgoing, true, ""))
    }

    @Test fun snapshotTakenBeforeCancellationCannotOverwriteCancellation() {
        val before = transfer()
        val cancelled = before.copy(status = TransferStatus.CANCELLED, approved = false)
        val state = state(cancelled)
        assertEquals(state, SessionPolicy.updateAttempt(state, before, before.copy(status = TransferStatus.COMPLETE)))
    }

    @Test fun completionCannotBeDowngradedByLateError() {
        val complete = transfer().copy(status = TransferStatus.COMPLETE)
        val state = state(complete)
        assertEquals(state, SessionPolicy.updateAttempt(state, complete, complete.copy(status = TransferStatus.FAILED)))
    }

    @Test fun changedRequestOrPayloadCannotBeUpdatedByAnOldAttempt() {
        val old = transfer()
        listOf(old.copy(requestId = "request-new-123"), old.copy(payloadId = 99), old.copy(status = TransferStatus.WAITING)).forEach { next ->
            val state = state(next)
            assertEquals(state, SessionPolicy.updateAttempt(state, old, old.copy(status = TransferStatus.COMPLETE)))
        }
    }

    @Test fun revokedPeerOrDeletedProfileCannotBeRecreatedByDelayedWork() {
        val transfer = transfer()
        val revoked = state(transfer).revokePeer(event, receiver)
        assertEquals(revoked, SessionPolicy.updateAttempt(revoked, transfer, transfer.copy(status = TransferStatus.COMPLETE)))
        assertEquals(AppState(), SessionPolicy.beginAttempt(AppState(), transfer))
        assertEquals(AppState(), SessionPolicy.updateAttempt(AppState(), transfer, transfer.copy(status = TransferStatus.COMPLETE)))
    }

    @Test fun retryGetsANewAttemptAndStaleMessagesCannotMutateIt() {
        val cancelled = transfer().copy(status = TransferStatus.CANCELLED)
        val next = cancelled.copy(requestId = "request-new-123", status = TransferStatus.AWAITING_APPROVAL, approved = false, payloadId = null)
        val changed = SessionPolicy.beginAttempt(state(cancelled), next)
        assertEquals(listOf(next), changed.transfers)
        assertEquals(changed, SessionPolicy.updateAttempt(changed, cancelled, cancelled.copy(status = TransferStatus.TRANSFERRING)))
    }

    @Test fun duplicateRequestsAndRequestIdsForAnotherPhotoDoNotCreateTransfers() {
        val waiting = transfer().copy(status = TransferStatus.AWAITING_APPROVAL, payloadId = null)
        val state = state(waiting)
        assertEquals(state, SessionPolicy.beginAttempt(state, waiting))
        assertEquals(state, SessionPolicy.beginAttempt(state, waiting.copy(photoId = "another-photo")))
    }

    @Test fun anActiveOriginalTransferCannotBeReplacedByARetry() {
        val active = transfer()
        val state = state(active)
        assertEquals(state, SessionPolicy.beginAttempt(state, active.copy(requestId = "request-new-123")))
    }

    @Test fun aSavedReceiveCannotBeRequestedAgainButOwnerCanApproveAFreshResend() {
        val send = transfer().copy(status = TransferStatus.COMPLETE)
        val resend = send.copy(requestId = "request-new-123", status = TransferStatus.AWAITING_APPROVAL, approved = false, payloadId = null)
        assertEquals(listOf(resend), SessionPolicy.beginAttempt(state(send), resend).transfers)
        val receive = send.copy(direction = TransferDirection.RECEIVE)
        val state = state(receive, receiver)
        assertEquals(state, SessionPolicy.beginAttempt(state, resend.copy(direction = TransferDirection.RECEIVE)))
    }

    @Test fun receiptMustMatchApprovedEventPhotoRequestAndChecksum() {
        val transfer = transfer()
        val receipt = WireMessage.Receipt(event, transfer.requestId, photo, hash)
        assertTrue(SessionPolicy.acceptsReceipt(transfer, receipt))
        listOf(receipt.copy(eventId = "another-event"), receipt.copy(photoId = "another-photo"),
            receipt.copy(requestId = "another-request"), receipt.copy(sha256 = "b".repeat(64))).forEach {
            assertFalse(SessionPolicy.acceptsReceipt(transfer, it))
        }
        assertFalse(SessionPolicy.acceptsReceipt(transfer.copy(approved = false), receipt))
        assertFalse(SessionPolicy.acceptsReceipt(transfer.copy(direction = TransferDirection.RECEIVE), receipt))
        assertFalse(SessionPolicy.acceptsReceipt(transfer.copy(status = TransferStatus.CANCELLED), receipt))
    }

    @Test fun sdkSuccessNeverMarksOriginalCompleteAndProgressCannotGoBackwards() {
        val transfer = transfer().copy(size = 10_000_000, bytesTransferred = 1_000_000)
        val completeBytes = SessionPolicy.progress(transfer, transfer.size, PayloadStatus.SUCCESS)
        assertEquals(TransferStatus.TRANSFERRING, completeBytes.status)
        assertEquals(transfer.size, completeBytes.bytesTransferred)
        assertEquals(transfer, SessionPolicy.progress(transfer, 100, PayloadStatus.IN_PROGRESS))
    }

    @Test fun smallProgressUpdatesAreCoalescedButFailuresArePersisted() {
        val transfer = transfer().copy(size = 10_000_000, bytesTransferred = 1_000_000)
        assertEquals(transfer, SessionPolicy.progress(transfer, 1_010_000, PayloadStatus.IN_PROGRESS))
        assertEquals(1_500_000L, SessionPolicy.progress(transfer, 1_500_000, PayloadStatus.IN_PROGRESS).bytesTransferred)
        val failed = SessionPolicy.progress(transfer, 1_010_000, PayloadStatus.FAILED)
        assertEquals(TransferStatus.FAILED, failed.status)
        assertEquals(1_010_000L, failed.bytesTransferred)
    }

    @Test fun publishingRequiresCurrentApprovedIncomingAttempt() {
        val receive = transfer().copy(direction = TransferDirection.RECEIVE)
        val state = state(receive, receiver)
        assertTrue(SessionPolicy.canReceive(state, receive))
        assertFalse(SessionPolicy.canReceive(state.revokePeer(event, owner), receive))
        assertFalse(SessionPolicy.canReceive(state.copy(transfers = listOf(receive.copy(status = TransferStatus.CANCELLED))), receive))
        assertFalse(SessionPolicy.canReceive(AppState(), receive))
    }

    @Test fun anAlreadyPublishedCopyIsRecordedEvenIfCancellationJustWonTheRace() {
        val receive = transfer().copy(direction = TransferDirection.RECEIVE)
        val cancelled = receive.copy(status = TransferStatus.CANCELLED, approved = false)
        val saved = SessionPolicy.recordSaved(state(cancelled, receiver), receive, "content://saved/1").transfers.single()
        assertEquals(TransferStatus.COMPLETE, saved.status)
        assertEquals("content://saved/1", saved.savedUri)
        assertEquals(AppState(), SessionPolicy.recordSaved(AppState(), receive, "content://saved/1"))
    }

    @Test fun aFastSessionRestartStillMarksCapturedOldTransfersWaiting() {
        val old = transfer()
        val connectedAgain = state(old).copy(peers = listOf(Peer(event, receiver, "Friend", faceRefs = listOf(listOf(1f)))))
        val disconnected = SessionPolicy.disconnect(connectedAgain, event, null, listOf(old), clearReferences = false)
        assertEquals(TransferStatus.WAITING, disconnected.transfers.single().status)
        assertNull(disconnected.transfers.single().payloadId)
        assertEquals(connectedAgain.peers, disconnected.peers)
    }

    @Test fun delayedDisconnectionCannotClobberAFreshAttemptOrCompletedSave() {
        val old = transfer()
        val fresh = old.copy(requestId = "request-new-123", status = TransferStatus.AWAITING_APPROVAL, payloadId = null)
        val state = state(fresh)
        assertEquals(state, SessionPolicy.disconnect(state, event, receiver, listOf(old), clearReferences = false))
        val complete = state(old.copy(status = TransferStatus.COMPLETE))
        assertEquals(complete, SessionPolicy.disconnect(complete, event, receiver, listOf(old), clearReferences = false))
    }

    private fun transfer() = Transfer(event, owner, photo, receiver, "request-12345678", TransferDirection.SEND,
        TransferStatus.TRANSFERRING, "photo.jpg", "image/jpeg", 100, hash, payloadId = 5, approved = true)

    private fun state(transfer: Transfer, me: String = owner) = AppState(profile = Profile(me, "Me"),
        events = listOf(Event(event, "Event", "a".repeat(64))),
        peers = listOf(Peer(event, if (me == owner) receiver else owner, "Friend")), transfers = listOf(transfer))
}
