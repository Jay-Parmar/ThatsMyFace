package com.thatsmyface

import com.thatsmyface.data.AppState
import com.thatsmyface.data.Transfer
import com.thatsmyface.data.TransferDirection
import com.thatsmyface.data.TransferStatus
import com.thatsmyface.data.SavedCopyAvailability
import com.thatsmyface.data.SavedCopyCheck
import com.thatsmyface.data.isApprovedPeer
import com.thatsmyface.data.upsertTransfer
import com.thatsmyface.nearby.PayloadStatus
import com.thatsmyface.nearby.WireMessage

internal object SessionPolicy {
    val terminal = setOf(TransferStatus.COMPLETE, TransferStatus.REJECTED, TransferStatus.CANCELLED)

    fun ownsAttempt(state: AppState, transfer: Transfer): Boolean {
        val localId = if (transfer.direction == TransferDirection.SEND) transfer.ownerId else transfer.receiverId
        val otherId = if (transfer.direction == TransferDirection.SEND) transfer.receiverId else transfer.ownerId
        return state.profile?.id == localId && state.isApprovedPeer(transfer.eventId, otherId)
    }

    fun sameAttempt(current: Transfer?, expected: Transfer): Boolean = current != null &&
        current.key == expected.key && current.requestId == expected.requestId &&
        current.direction == expected.direction && current.payloadId == expected.payloadId &&
        current.status == expected.status && current.approved == expected.approved

    fun updateAttempt(state: AppState, expected: Transfer, next: Transfer): AppState {
        val current = state.transfers.find { it.key == expected.key }
        if (!sameAttempt(current, expected) || current?.status in terminal || !ownsAttempt(state, expected)) return state
        return state.upsertTransfer(next)
    }

    fun beginAttempt(state: AppState, transfer: Transfer): AppState {
        if (!ownsAttempt(state, transfer)) return state
        val existing = state.transfers.find { it.key == transfer.key }
        if (existing?.requestId == transfer.requestId || existing?.status in setOf(TransferStatus.QUEUED, TransferStatus.TRANSFERRING)) return state
        if (existing?.status == TransferStatus.COMPLETE && transfer.direction == TransferDirection.RECEIVE &&
            existing.savedCopyAvailability != SavedCopyAvailability.MISSING) return state
        if (state.transfers.any { it.eventId == transfer.eventId && it.requestId == transfer.requestId && it.key != transfer.key }) return state
        return state.copy(transfers = state.transfers.filterNot { it.key == transfer.key } + transfer)
    }

    fun decision(transfer: Transfer, approved: Boolean, reason: String): Transfer =
        if (transfer.direction != TransferDirection.RECEIVE || transfer.status != TransferStatus.AWAITING_APPROVAL) transfer
        else transfer.copy(status = if (approved) TransferStatus.QUEUED else TransferStatus.REJECTED,
            approved = approved, error = reason.takeIf(String::isNotBlank))

    fun acceptsReceipt(transfer: Transfer, receipt: WireMessage.Receipt): Boolean =
        transfer.direction == TransferDirection.SEND && transfer.approved &&
            transfer.eventId == receipt.eventId && transfer.requestId == receipt.requestId &&
            transfer.photoId == receipt.photoId && transfer.sha256 == receipt.sha256 &&
            transfer.status in setOf(TransferStatus.QUEUED, TransferStatus.TRANSFERRING, TransferStatus.WAITING, TransferStatus.FAILED)

    fun canReceive(state: AppState, expected: Transfer): Boolean =
        expected.direction == TransferDirection.RECEIVE && expected.status == TransferStatus.TRANSFERRING &&
            expected.approved && ownsAttempt(state, expected) && sameAttempt(state.transfers.find { it.key == expected.key }, expected)

    fun recordSaved(state: AppState, expected: Transfer, savedUri: String): AppState {
        val current = state.transfers.find { it.key == expected.key } ?: return state
        if (state.profile?.id != expected.receiverId || current.direction != TransferDirection.RECEIVE ||
            current.sha256 != expected.sha256 || current.size != expected.size) return state
        // A copy published just before cancellation is already downloaded and must not be saved twice.
        return state.copy(transfers = state.transfers.map {
            if (it.key == expected.key) it.copy(status = TransferStatus.COMPLETE, savedUri = savedUri,
                bytesTransferred = expected.size, error = null, savedCopyAvailability = SavedCopyAvailability.AVAILABLE) else it
        })
    }

    fun recordSavedCheck(state: AppState, expected: Transfer, result: SavedCopyCheck): AppState {
        val current = state.transfers.find { it.key == expected.key } ?: return state
        if (state.profile?.id != expected.receiverId || current.direction != TransferDirection.RECEIVE ||
            current.status != TransferStatus.COMPLETE || !sameAttempt(current, expected) ||
            current.savedUri != expected.savedUri || current.sha256 != expected.sha256 || current.size != expected.size) return state
        return state.copy(transfers = state.transfers.map {
            if (it.key == current.key) it.copy(savedCopyAvailability = result.availability, error = result.message) else it
        })
    }

    fun disconnect(state: AppState, eventId: String, peerId: String?, interrupted: List<Transfer>, clearReferences: Boolean): AppState = state.copy(
        peers = state.peers.map {
            if (clearReferences && it.eventId == eventId && (peerId == null || it.peerId == peerId)) it.copy(faceRefs = emptyList()) else it
        },
        transfers = state.transfers.map {
            if (it.status !in terminal && interrupted.any { old -> sameAttempt(it, old) }) {
                it.copy(status = TransferStatus.WAITING, payloadId = null, error = "Waiting for your friend's phone. Reconnect and retry.")
            } else it
        },
    )

    fun progress(transfer: Transfer, bytes: Long, status: PayloadStatus): Transfer {
        if (transfer.status != TransferStatus.TRANSFERRING) return transfer
        val nextBytes = bytes.coerceIn(transfer.bytesTransferred, transfer.size)
        val nextStatus = when (status) {
            PayloadStatus.FAILED -> TransferStatus.FAILED
            PayloadStatus.CANCELLED -> TransferStatus.CANCELLED
            else -> transfer.status
        }
        val threshold = maxOf(256 * 1024L, transfer.size / 50)
        if (status == PayloadStatus.IN_PROGRESS && nextBytes - transfer.bytesTransferred < threshold && nextBytes != transfer.size) return transfer
        return transfer.copy(bytesTransferred = nextBytes, status = nextStatus,
            approved = transfer.approved && status != PayloadStatus.CANCELLED,
            error = when (status) {
                PayloadStatus.FAILED -> "Transfer interrupted. Reconnect and retry."
                PayloadStatus.CANCELLED -> "Transfer cancelled. Request it again to retry."
                else -> transfer.error
            })
    }
}
