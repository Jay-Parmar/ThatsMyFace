package com.thatsmyface.nearby

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal class MessageDeliveryTracker(private val timeoutMillis: Long = 20_000) {
    private data class Pending(val endpointId: String, val result: CompletableDeferred<Unit>)
    private val pending = mutableMapOf<Long, Pending>()

    suspend fun send(endpointId: String, payloadId: Long, enqueue: suspend () -> Unit, cancel: () -> Unit) {
        check(payloadId !in pending) { "Message already pending" }
        val message = Pending(endpointId, CompletableDeferred())
        pending[payloadId] = message
        var delivered = false
        try {
            // The SDK send task only confirms enqueueing. Delivery has a separate callback.
            val completed = withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    val enqueued = async { enqueue() }
                    message.result.await()
                    enqueued.await()
                }
                true
            } ?: false
            if (!completed) throw IOException("Message delivery timed out. Reconnect and retry.")
            delivered = true
        } finally {
            if (pending[payloadId] === message) pending.remove(payloadId)
            if (!delivered) runCatching(cancel)
        }
    }

    fun update(endpointId: String, payloadId: Long, status: PayloadStatus): Boolean {
        val message = pending[payloadId]?.takeIf { it.endpointId == endpointId } ?: return false
        when (status) {
            PayloadStatus.SUCCESS -> message.result.complete(Unit)
            PayloadStatus.FAILED, PayloadStatus.CANCELLED -> message.result.completeExceptionally(IOException("Message could not be delivered. Reconnect and retry."))
            PayloadStatus.IN_PROGRESS -> Unit
        }
        return true
    }

    fun disconnect(endpointId: String) {
        pending.entries.filter { it.value.endpointId == endpointId }.forEach { (id, message) ->
            pending.remove(id)
            message.result.completeExceptionally(IOException("Friend disconnected before message delivery."))
        }
    }

    fun clear() {
        val messages = pending.values.toList()
        pending.clear()
        messages.forEach { it.result.completeExceptionally(IOException("Sharing ended before message delivery.")) }
    }
}
