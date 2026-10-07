package com.thatsmyface.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.MainThread
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PeerStatus { DISCOVERED, CONNECTING, VERIFYING, WAITING_FOR_PEER, CONNECTED, VERIFIED, DISCONNECTED }
enum class PayloadStatus { IN_PROGRESS, SUCCESS, FAILED, CANCELLED }

data class NearbyPeer(
    val endpointId: String,
    val nickname: String,
    val status: PeerStatus,
    val authenticationDigits: String? = null,
)

sealed interface TransportEvent {
    data class Connected(val endpointId: String, val authenticationDigits: String) : TransportEvent
    data class Message(val endpointId: String, val message: WireMessage) : TransportEvent
    data class FileReceived(val endpointId: String, val payloadId: Long, val uri: Uri) : TransportEvent
    data class Progress(val endpointId: String, val payloadId: Long, val bytesTransferred: Long, val totalBytes: Long, val status: PayloadStatus) : TransportEvent
    data class Disconnected(val endpointId: String) : TransportEvent
    data class Error(val endpointId: String?, val message: String) : TransportEvent
}

class PreparedFile internal constructor(internal val payload: Payload, private val descriptor: ParcelFileDescriptor?) : Closeable {
    val payloadId: Long get() = payload.id
    override fun close() { descriptor?.close() }
}

@SuppressLint("MissingPermission")
@MainThread
class NearbyTransport(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val client by lazy { Nearby.getConnectionsClient(this.context) }
    private val mutablePeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = mutablePeers.asStateFlow()
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TransportEvent> = mutableEvents.asSharedFlow()
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    private var eventId: String? = null
    private var nickname = ""
    private var generation = 0
    private val accepted = mutableSetOf<String>()
    private val allowed = mutableSetOf<String>()
    private val receivedHello = mutableSetOf<String>()
    private val expectedFiles = mutableMapOf<Long, ExpectedFile>()
    private val incoming = mutableMapOf<Long, Payload>()
    private val outgoing = mutableMapOf<Long, PreparedFile>()
    private val payloadPeers = mutableMapOf<Long, String>()

    suspend fun start(eventId: String, nickname: String) {
        WireCodec.requireId(eventId)
        require(nickname.isNotBlank() && nickname.length <= 40) { "Choose a nickname of 1 to 40 characters" }
        stop()
        check(NearbyPermissions.missing(context).isEmpty()) { "Allow nearby device permissions before sharing" }
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        check(availability == ConnectionResult.SUCCESS) { "Install or update Google Play services before sharing (code $availability)" }
        this.eventId = eventId
        this.nickname = nickname
        val session = generation
        val lifecycle = lifecycleCallback(session)
        val serviceId = "com.thatsmyface.$eventId"
        mutableActive.value = true
        try {
            client.startAdvertising(nickname, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).awaitResult()
            check(session == generation) { "Sharing session ended" }
            client.startDiscovery(serviceId, discoveryCallback(session), DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).awaitResult()
        } catch (failure: Exception) {
            if (session == generation) stop()
            if (failure is CancellationException) throw failure
            throw IllegalStateException(explainFailure(failure), failure)
        }
    }

    fun stop() {
        generation++
        if (eventId != null) {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        mutablePeers.value.filter { it.status != PeerStatus.DISCOVERED }.forEach {
            emit(TransportEvent.Disconnected(it.endpointId))
        }
        incoming.values.forEach(::removeReceivedFile)
        outgoing.values.forEach { runCatching { it.close() } }
        incoming.clear()
        outgoing.clear()
        expectedFiles.clear()
        payloadPeers.clear()
        accepted.clear()
        allowed.clear()
        receivedHello.clear()
        eventId = null
        mutableActive.value = false
        mutablePeers.value = emptyList()
    }

    suspend fun connect(endpointId: String) {
        check(active.value && peer(endpointId)?.status == PeerStatus.DISCOVERED) { "Friend is no longer available. Search again" }
        updatePeer(endpointId) { it.copy(status = PeerStatus.CONNECTING) }
        try {
            client.requestConnection(nickname, endpointId, lifecycleCallback(generation)).awaitResult()
        } catch (failure: Exception) {
            updatePeer(endpointId) { it.copy(status = PeerStatus.DISCOVERED) }
            if (failure is CancellationException) { disconnect(endpointId); throw failure }
            throw IllegalStateException(explainFailure(failure), failure)
        }
    }

    suspend fun verify(endpointId: String) {
        val peer = peer(endpointId)
        check(peer?.status == PeerStatus.VERIFYING && !peer.authenticationDigits.isNullOrBlank()) { "Compare the code on both phones first" }
        accepted += endpointId
        updatePeer(endpointId) { it.copy(status = PeerStatus.WAITING_FOR_PEER) }
        try {
            client.acceptConnection(endpointId, payloadCallback(generation)).awaitResult()
        } catch (failure: Exception) {
            accepted -= endpointId
            disconnect(endpointId)
            if (failure is CancellationException) throw failure
            throw IllegalStateException(explainFailure(failure), failure)
        }
    }

    fun reject(endpointId: String) {
        client.rejectConnection(endpointId)
        clearPeer(endpointId)
    }

    fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        clearPeer(endpointId)
    }

    fun allowPeer(endpointId: String) {
        check(peer(endpointId)?.status == PeerStatus.CONNECTED && endpointId in accepted) { "Verify this friend first" }
        allowed += endpointId
        updatePeer(endpointId) { it.copy(status = PeerStatus.VERIFIED) }
    }

    suspend fun sendMessage(endpointId: String, message: WireMessage) {
        check(message.eventId == eventId) { "Wrong event" }
        requireConnection(endpointId, message is WireMessage.Hello)
        client.sendPayload(endpointId, Payload.fromBytes(WireCodec.encode(message))).awaitResult()
    }

    fun prepareFile(file: File): PreparedFile = PreparedFile(Payload.fromFile(file), null)
    fun prepareFile(descriptor: ParcelFileDescriptor): PreparedFile = PreparedFile(Payload.fromFile(descriptor), descriptor)

    suspend fun sendFile(endpointId: String, prepared: PreparedFile) {
        requireConnection(endpointId)
        check(prepared.payloadId !in outgoing) { "Transfer already started" }
        outgoing[prepared.payloadId] = prepared
        payloadPeers[prepared.payloadId] = endpointId
        try {
            client.sendPayload(endpointId, prepared.payload).awaitResult()
        } catch (failure: Exception) {
            client.cancelPayload(prepared.payloadId)
            outgoing.remove(prepared.payloadId)?.close()
            payloadPeers.remove(prepared.payloadId)
            if (failure is CancellationException) throw failure
            throw IllegalStateException(explainFailure(failure), failure)
        }
    }

    fun expectFile(endpointId: String, payloadId: Long, byteCount: Long) {
        requireConnection(endpointId)
        require(byteCount in 1..WireCodec.MAX_PHOTO_BYTES) { "Invalid file size" }
        check(expectedFiles.size < 16 && payloadId !in expectedFiles && payloadId !in outgoing) { "Too many pending files or duplicate payload" }
        expectedFiles[payloadId] = ExpectedFile(endpointId, byteCount)
    }

    fun cancel(payloadId: Long) {
        client.cancelPayload(payloadId)
        incoming.remove(payloadId)?.let(::removeReceivedFile)
        outgoing.remove(payloadId)?.let { runCatching { it.close() } }
        expectedFiles.remove(payloadId)
        payloadPeers.remove(payloadId)
    }

    private fun discoveryCallback(session: Int) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (session != generation || !active.value || mutablePeers.value.size >= 32) return
            if (peer(endpointId) == null || peer(endpointId)?.status == PeerStatus.DISCONNECTED) {
                mutablePeers.value = mutablePeers.value.filterNot { it.endpointId == endpointId } +
                    NearbyPeer(endpointId, safeNickname(info.endpointName), PeerStatus.DISCOVERED)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            if (session != generation) return
            if (peer(endpointId)?.status == PeerStatus.DISCOVERED) {
                mutablePeers.value = mutablePeers.value.filterNot { it.endpointId == endpointId }
            }
        }
    }

    private fun lifecycleCallback(session: Int) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (session != generation || !active.value || mutablePeers.value.count { it.status != PeerStatus.DISCOVERED } >= 16) {
                client.rejectConnection(endpointId)
                return
            }
            if (info.authenticationDigits.isNullOrBlank()) {
                client.rejectConnection(endpointId)
                emit(TransportEvent.Error(endpointId, "This connection has no verification code. Reconnect"))
                return
            }
            mutablePeers.value = mutablePeers.value.filterNot { it.endpointId == endpointId } +
                NearbyPeer(endpointId, safeNickname(info.endpointName), PeerStatus.VERIFYING, info.authenticationDigits)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (session != generation) return
            val digits = peer(endpointId)?.authenticationDigits
            if (result.status.isSuccess && endpointId in accepted && !digits.isNullOrBlank()) {
                updatePeer(endpointId) { it.copy(status = PeerStatus.CONNECTED) }
                emit(TransportEvent.Connected(endpointId, digits))
            } else {
                disconnect(endpointId)
                emit(TransportEvent.Error(endpointId, "Connection declined or interrupted. Ask your friend to reconnect"))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (session == generation) clearPeer(endpointId)
        }
    }

    private fun payloadCallback(session: Int) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (session != generation) {
                client.cancelPayload(payload.id)
                if (payload.type == Payload.Type.FILE) removeReceivedFile(payload)
                return
            }
            when (payload.type) {
                Payload.Type.BYTES -> receiveMessage(endpointId, payload)
                Payload.Type.FILE -> {
                    val expectation = expectedFiles[payload.id]
                    val fileSize = payload.asFile()?.size ?: -1L
                    if (endpointId !in allowed || expectation == null || expectation.endpointId != endpointId || fileSize > expectation.byteCount) {
                        client.cancelPayload(payload.id)
                        removeReceivedFile(payload)
                        emit(TransportEvent.Error(endpointId, "An unrequested file was blocked"))
                        return
                    }
                    incoming[payload.id] = payload
                    payloadPeers[payload.id] = endpointId
                }
                else -> { client.cancelPayload(payload.id); disconnect(endpointId) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (session != generation || payloadPeers[update.payloadId] != endpointId) return
            val expectation = expectedFiles[update.payloadId]
            if (expectation != null && (update.bytesTransferred > expectation.byteCount || update.totalBytes > expectation.byteCount)) {
                cancel(update.payloadId)
                emit(TransportEvent.Error(endpointId, "The received file exceeded its approved size"))
                return
            }
            val status = when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> PayloadStatus.SUCCESS
                PayloadTransferUpdate.Status.FAILURE -> PayloadStatus.FAILED
                PayloadTransferUpdate.Status.CANCELED -> PayloadStatus.CANCELLED
                else -> PayloadStatus.IN_PROGRESS
            }
            emit(TransportEvent.Progress(endpointId, update.payloadId, update.bytesTransferred, update.totalBytes, status))
            if (status == PayloadStatus.IN_PROGRESS) return
            val received = incoming.remove(update.payloadId)
            if (status == PayloadStatus.SUCCESS && received != null) {
                val uri = received.asFile()?.asUri()
                if (uri != null) emit(TransportEvent.FileReceived(endpointId, update.payloadId, uri))
                else emit(TransportEvent.Error(endpointId, "The received file could not be opened. Request it again"))
            } else if (received != null) removeReceivedFile(received)
            outgoing.remove(update.payloadId)?.let { runCatching { it.close() } }
            expectedFiles.remove(update.payloadId)
            payloadPeers.remove(update.payloadId)
        }
    }

    private fun receiveMessage(endpointId: String, payload: Payload) {
        try {
            requireConnection(endpointId, allowHello = true)
            val message = WireCodec.decode(requireNotNull(payload.asBytes()), requireNotNull(eventId))
            check(message is WireMessage.Hello || endpointId in allowed) { "Unverified event peer" }
            if (message is WireMessage.Hello) check(receivedHello.add(endpointId)) { "Identity already announced" }
            emit(TransportEvent.Message(endpointId, message))
        } catch (_: Exception) {
            disconnect(endpointId)
            emit(TransportEvent.Error(endpointId, "An invalid or unauthorized message was blocked. Reconnect to try again"))
        }
    }

    private fun clearPeer(endpointId: String) {
        accepted -= endpointId
        allowed -= endpointId
        receivedHello -= endpointId
        payloadPeers.filterValues { it == endpointId }.keys.toList().forEach(::cancel)
        expectedFiles.entries.removeAll { it.value.endpointId == endpointId }
        updatePeer(endpointId) { it.copy(status = PeerStatus.DISCONNECTED, authenticationDigits = null) }
        emit(TransportEvent.Disconnected(endpointId))
    }

    private fun requireConnection(endpointId: String, allowHello: Boolean = false) {
        check(active.value && endpointId in accepted && peer(endpointId)?.status in setOf(PeerStatus.CONNECTED, PeerStatus.VERIFIED)) { "Friend is offline. Start sharing on both phones and reconnect" }
        check(allowHello || endpointId in allowed) { "Verify this event friend first" }
    }

    private fun removeReceivedFile(payload: Payload) {
        payload.asFile()?.asUri()?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    private fun peer(endpointId: String) = mutablePeers.value.find { it.endpointId == endpointId }

    private fun updatePeer(endpointId: String, transform: (NearbyPeer) -> NearbyPeer) {
        mutablePeers.value = mutablePeers.value.map { if (it.endpointId == endpointId) transform(it) else it }
    }

    private fun emit(event: TransportEvent) {
        scope.launch { mutableEvents.emit(event) }
    }

    private fun safeNickname(value: String) = value.filterNot { it.isISOControl() }.take(40).ifBlank { "Nearby friend" }

    private fun explainFailure(failure: Exception): String = when (failure) {
        is SecurityException -> "Nearby permission was removed. Allow it in app settings and start sharing again"
        is ApiException -> "Nearby could not connect (code ${failure.statusCode}). Turn on Bluetooth, Wi-Fi and location, then retry"
        else -> "Nearby sharing stopped. Check Bluetooth, Wi-Fi and Google Play services, then retry"
    }

    private data class ExpectedFile(val endpointId: String, val byteCount: Long)
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
