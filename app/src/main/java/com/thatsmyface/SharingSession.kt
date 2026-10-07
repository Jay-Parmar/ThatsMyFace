package com.thatsmyface

import android.content.Context
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.data.LocalStore
import com.thatsmyface.data.MatchDecision
import com.thatsmyface.data.MatchKind
import com.thatsmyface.data.Peer
import com.thatsmyface.data.Photo
import com.thatsmyface.data.PhotoAvailability
import com.thatsmyface.data.PhotoFiles
import com.thatsmyface.data.PhotoOffer
import com.thatsmyface.data.Transfer
import com.thatsmyface.data.TransferDirection
import com.thatsmyface.data.TransferStatus
import com.thatsmyface.data.canAccessPhoto
import com.thatsmyface.data.canSendOriginal
import com.thatsmyface.data.decideMatch
import com.thatsmyface.data.digest
import com.thatsmyface.data.isApprovedPeer
import com.thatsmyface.data.newId
import com.thatsmyface.data.revokePeer
import com.thatsmyface.data.transferKey
import com.thatsmyface.nearby.InvitationProof
import com.thatsmyface.nearby.NearbyLink
import com.thatsmyface.nearby.NearbyTransport
import com.thatsmyface.nearby.PayloadStatus
import com.thatsmyface.nearby.PeerStatus
import com.thatsmyface.nearby.PreparedFile
import com.thatsmyface.nearby.TransportEvent
import com.thatsmyface.nearby.WireMessage
import com.thatsmyface.recognition.FaceEngine
import com.thatsmyface.recognition.FaceMatcher
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class SharingSession(
    private val context: Context,
    private val store: LocalStore,
    private val files: PhotoFiles,
    private val engine: FaceEngine,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    val transport: NearbyLink = NearbyTransport(context, scope),
) {
    val eventId = MutableStateFlow<String?>(null)
    private val identities = mutableMapOf<String, String>()
    private val digits = mutableMapOf<String, String>()
    private val readyPeers = mutableSetOf<String>()
    private val refreshJobs = mutableMapOf<String, Job>()
    private val receivingJobs = mutableMapOf<Long, Job>()
    private val prepared = mutableMapOf<Long, Pair<PreparedFile, File>>()
    private val receivedDecisions = linkedMapOf<String, MatchDecision>()
    @Volatile private var generation = 0
    private val current: AppState get() = store.state.value

    init {
        scope.launch {
            transport.events.collect { event ->
                try { handle(event) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notify("Sharing could not finish. Check access, keep both apps open, and retry.") }
            }
        }
    }

    suspend fun start(event: Event) {
        stop()
        val session = generation
        store.update { state ->
            if (session != generation) state
            else state.copy(peers = state.peers.map { it.copy(faceRefs = emptyList()) })
        }
        if (session != generation) return
        val profile = requireNotNull(current.profile)
        eventId.value = event.id
        try { transport.start(event.id, profile.nickname) }
        catch (error: Exception) { eventId.value = null; throw error }
    }

    fun stop() {
        generation++
        val endingGeneration = generation
        val endedEvent = eventId.value
        val interrupted = current.transfers.filter { it.eventId == endedEvent && it.status !in terminalStates }
        eventId.value = null
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        receivingJobs.values.forEach { it.cancel() }
        receivingJobs.clear()
        receivedDecisions.clear()
        identities.clear()
        digits.clear()
        readyPeers.clear()
        transport.stop()
        prepared.values.forEach { (payload, file) -> runCatching { payload.close() }; file.delete() }
        prepared.clear()
        if (endedEvent != null) scope.launch { markDisconnected(endedEvent, null, endingGeneration, interrupted) }
    }

    private suspend fun handle(event: TransportEvent) {
        when (event) {
            is TransportEvent.Connected -> {
                val localEvent = current.events.find { it.id == eventId.value } ?: return
                val profile = current.profile ?: return
                digits[event.endpointId] = event.authenticationDigits
                transport.sendMessage(event.endpointId, WireMessage.Hello(localEvent.id, profile.id, profile.nickname,
                    InvitationProof.create(localEvent.secret, localEvent.id, profile.id, profile.nickname, event.authenticationDigits)))
            }
            is TransportEvent.Message -> handleMessage(event.endpointId, event.message)
            is TransportEvent.Disconnected -> {
                val peerId = identities.remove(event.endpointId)
                readyPeers -= event.endpointId
                digits.remove(event.endpointId)
                refreshJobs.remove(event.endpointId)?.cancel()
                if (peerId != null) eventId.value?.let { active ->
                    current.transfers.filter { it.eventId == active && (it.ownerId == peerId || it.receiverId == peerId) }
                        .mapNotNull { it.payloadId }.forEach { receivingJobs.remove(it)?.cancel(); releasePrepared(it) }
                    markDisconnected(active, peerId)
                }
            }
            is TransportEvent.FileReceived -> {
                if (event.payloadId !in receivingJobs) {
                    receivingJobs[event.payloadId] = scope.launch {
                        try { receiveFile(event) } finally { receivingJobs.remove(event.payloadId) }
                    }
                }
            }
            is TransportEvent.Progress -> updateProgress(event)
            is TransportEvent.Error -> notify(event.message)
        }
    }

    private suspend fun handleMessage(endpoint: String, message: WireMessage) {
        val active = eventId.value ?: return
        if (message.eventId != active) { transport.disconnect(endpoint); return }
        if (message is WireMessage.Hello) {
            val session = generation
            val event = current.events.find { it.id == active } ?: return
            val token = digits[endpoint] ?: return
            val denied = current.peers.any { it.eventId == active && it.peerId == message.peerId && !it.allowed }
            if (denied || message.peerId == current.profile?.id || !InvitationProof.verify(event.secret, message, token) ||
                identities.any { it.key != endpoint && it.value == message.peerId }) {
                transport.disconnect(endpoint)
                notify("This friend could not be admitted to the event. Check the invitation or removed access.")
                return
            }
            store.update { state ->
                if (state.events.none { it.id == active } || state.profile == null || state.peers.any { it.eventId == active && it.peerId == message.peerId && !it.allowed }) state
                else state.copy(peers = state.peers.filterNot { p -> p.eventId == active && p.peerId == message.peerId } + Peer(active, message.peerId, message.nickname))
            }
            if (session != generation || eventId.value != active || !current.isApprovedPeer(active, message.peerId) ||
                transport.peers.value.none { it.endpointId == endpoint && it.status == PeerStatus.CONNECTED }) return
            identities[endpoint] = message.peerId
            transport.allowPeer(endpoint)
            transport.sendMessage(endpoint, WireMessage.EventReady(active))
            return
        }
        val peer = identities[endpoint] ?: return
        if (!current.isApprovedPeer(active, peer)) return
        if (message !is WireMessage.EventReady && endpoint !in readyPeers) return
        when (message) {
            is WireMessage.Hello -> Unit
            is WireMessage.EventReady -> {
                if (!readyPeers.add(endpoint)) return
                sendReferences(endpoint)
                current.transfers.filter { it.eventId == active && it.ownerId == peer && it.direction == TransferDirection.RECEIVE && it.status == TransferStatus.COMPLETE }.forEach {
                    if (validPeer(endpoint, active, peer)) transport.sendMessage(endpoint, WireMessage.Receipt(active, it.requestId, it.photoId, it.sha256))
                }
                refresh(endpoint)
            }
            is WireMessage.Catalog -> {
                if (message.reset) {
                    current.offers.filter { it.eventId == active && it.ownerId == peer }.forEach {
                        receivedDecisions["$active:$peer:${it.photoId}"] = it.decision
                    }
                    while (receivedDecisions.size > 4000) receivedDecisions.remove(receivedDecisions.keys.first())
                }
                val decisions = receivedDecisions.toMap()
                store.update { state ->
                    if (!state.isApprovedPeer(active, peer)) return@update state
                    val previous = state.offers.filter { it.eventId == active && it.ownerId == peer }.associateBy { it.photoId }
                    val incoming = message.photos.map { photo -> PhotoOffer(active, peer, photo.photoId, photo.fileName, photo.mimeType, photo.byteCount,
                        sha256 = "", thumbnailBase64 = photo.previewBase64, match = when (photo.matchState) {
                            "SUGGESTED" -> MatchKind.SUGGESTED
                            "UNCERTAIN" -> MatchKind.UNCERTAIN
                            "MANUAL" -> MatchKind.MANUAL
                            else -> MatchKind.AVAILABLE
                        }, decision = previous[photo.photoId]?.decision ?: decisions["$active:$peer:${photo.photoId}"] ?: MatchDecision.NONE) }
                    val old = state.offers.filterNot { it.eventId == active && it.ownerId == peer && (message.reset || message.photos.any { p -> p.photoId == it.photoId }) }
                    check(old.size + incoming.size <= 4000) { "Event library is full." }
                    state.copy(offers = old + incoming)
                }
            }
            is WireMessage.FaceReferences -> {
                if (message.modelId != FACE_MODEL || message.embeddings.any { it.size != FaceMatcher.EMBEDDING_SIZE }) return
                store.update { it.copy(peers = it.peers.map { p -> if (p.eventId == active && p.peerId == peer && p.allowed) p.copy(faceRefs = message.embeddings) else p }) }
                refreshAll()
            }
            is WireMessage.Request -> receiveRequest(endpoint, peer, message)
            is WireMessage.Decision -> {
                updateTransfer(message.requestId, peer, message.photoId, TransferDirection.RECEIVE) {
                    SessionPolicy.decision(it, message.approved, message.reason)
                }
            }
            is WireMessage.FileReady -> receiveMetadata(endpoint, peer, message)
            is WireMessage.Ready -> {
                val transfer = current.transfers.find { it.eventId == active && it.requestId == message.requestId && it.receiverId == peer && it.direction == TransferDirection.SEND && it.payloadId == message.payloadId && it.status == TransferStatus.QUEUED } ?: return
                val file = prepared[message.payloadId] ?: return
                if (!current.canSendOriginal(transfer)) return
                val sending = transfer.copy(status = TransferStatus.TRANSFERRING)
                if (!changeTransfer(transfer, sending)) { releasePrepared(message.payloadId); return }
                try {
                    if (validPeer(endpoint, active, peer) && SessionPolicy.sameAttempt(current.transfers.find { it.key == sending.key }, sending) && current.canSendOriginal(sending)) {
                        transport.sendFile(endpoint, file.first)
                    } else releasePrepared(message.payloadId)
                } catch (error: Exception) {
                    releasePrepared(message.payloadId)
                    changeTransfer(sending, sending.copy(status = TransferStatus.FAILED, error = "Original could not be sent. Reconnect and retry."))
                    if (error is CancellationException) throw error
                }
            }
            is WireMessage.Receipt -> {
                val transfer = current.transfers.find { it.eventId == active && it.requestId == message.requestId && it.photoId == message.photoId && it.receiverId == peer && it.direction == TransferDirection.SEND } ?: return
                if (SessionPolicy.acceptsReceipt(transfer, message)) {
                    changeTransfer(transfer, transfer.copy(status = TransferStatus.COMPLETE, bytesTransferred = transfer.size, error = null))
                    transfer.payloadId?.let(::releasePrepared)
                }
            }
            is WireMessage.MatchDecision -> {
                val photo = current.photos.find { it.eventId == active && it.id == message.photoId } ?: return
                store.update { state -> state.copy(photos = state.photos.map {
                    if (it.eventId == active && it.id == photo.id && state.isApprovedPeer(active, peer)) it.copy(manualPersonIds = if (message.decision == "REJECTED") it.manualPersonIds - peer else (it.manualPersonIds + peer).distinct()) else it
                }) }
            }
            is WireMessage.Revoke -> {
                if (message.faceDataOnly) {
                    store.update { it.copy(peers = it.peers.map { p -> if (p.eventId == active && p.peerId == peer) p.copy(faceRefs = emptyList()) else p }) }
                    refreshAll()
                } else {
                    store.update { it.revokePeer(active, peer) }
                    transport.disconnect(endpoint)
                }
            }
            is WireMessage.Cancel -> {
                val transfer = current.transfers.find { it.eventId == active && it.requestId == message.requestId && it.photoId == message.photoId && (it.ownerId == peer || it.receiverId == peer) } ?: return
                cancel(transfer, sendMessage = false)
            }
            is WireMessage.Error -> {
                val transfer = current.transfers.find { it.eventId == active && it.requestId == message.requestId && (it.ownerId == peer || it.receiverId == peer) }
                transfer?.let {
                    if (it.status !in terminalStates) {
                        it.payloadId?.let { payload -> transport.cancel(payload); receivingJobs.remove(payload)?.cancel(); releasePrepared(payload) }
                        changeTransfer(it, it.copy(status = TransferStatus.FAILED, payloadId = null, error = "Friend could not finish this transfer. Check access, then retry."))
                    }
                }
            }
        }
    }

    suspend fun sendReferences(endpoint: String? = null) {
        val active = eventId.value ?: return
        val event = current.events.find { it.id == active } ?: return
        val refs = current.profile?.faceRefs.orEmpty()
        val message = if (event.shareFaceData && refs.isNotEmpty()) WireMessage.FaceReferences(active, FACE_MODEL, refs)
            else WireMessage.Revoke(active, faceDataOnly = true)
        val targets = if (endpoint != null) listOf(endpoint) else identities.keys.toList()
        targets.forEach { target -> if (identities[target]?.let { validPeer(target, active, it) } == true) transport.sendMessage(target, message) }
    }

    fun refreshAll() { identities.keys.toList().forEach(::refresh) }

    private fun refresh(endpoint: String) {
        refreshJobs.remove(endpoint)?.cancel()
        val peer = identities[endpoint] ?: return
        val active = eventId.value ?: return
        val session = generation
        refreshJobs[endpoint] = scope.launch {
            try {
                var recognitionFailed = false
                if (!validPeer(endpoint, active, peer)) return@launch
                transport.sendMessage(endpoint, WireMessage.Catalog(active, emptyList(), reset = true))
                val participants = current.peers.filter { it.eventId == active && it.allowed && it.faceRefs.isNotEmpty() }.associate { it.peerId to it.faceRefs.map(List<Float>::toFloatArray) }
                for (photo in current.photos.filter { it.eventId == active && it.availability == PhotoAvailability.AVAILABLE }) {
                    val verifiedPhoto = files.checkAvailability(photo)
                    if (verifiedPhoto.availability != PhotoAvailability.AVAILABLE) {
                        store.update { it.copy(photos = it.photos.map { p -> if (p.eventId == active && p.id == photo.id && p.sha256 == photo.sha256) verifiedPhoto else p }) }
                        continue
                    }
                    val preview = try { files.thumbnail(photo) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    var kind = if (peer in photo.manualPersonIds) "MANUAL" else "NONE"
                    if (kind == "NONE" && participants.isNotEmpty()) {
                        val faces = try { engine.extract(android.net.Uri.parse(photo.uri)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { recognitionFailed = true; emptyList() }
                        val matches = faces.map { FaceMatcher.match(it.embedding, participants, it.issues.isEmpty()) }.filter { it.participantId == peer }
                        kind = when {
                            matches.any { it.kind == com.thatsmyface.recognition.MatchKind.SUGGESTED } -> "SUGGESTED"
                            matches.isNotEmpty() -> "UNCERTAIN"
                            else -> "NONE"
                        }
                    }
                    if (session != generation || !validPeer(endpoint, active, peer) || !current.canAccessPhoto(active, peer, photo.id)) return@launch
                    transport.sendMessage(endpoint, WireMessage.Catalog(active, listOf(com.thatsmyface.nearby.PhotoOffer(photo.id, photo.displayName, photo.mimeType, photo.size, preview, kind))))
                }
                if (recognitionFailed) notify("Some face matches could not be checked. Retry discovery or use manual tags for those photos.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notify("Some previews could not be shared. Check photo access and tap refresh.") }
        }
    }

    suspend fun request(offer: PhotoOffer) {
        val me = requireNotNull(current.profile).id
        check(current.isApprovedPeer(offer.eventId, offer.ownerId)) { "This friend no longer has event access." }
        val existing = current.transfers.find { it.key == transferKey(offer.eventId, offer.ownerId, offer.photoId, me) }
        if (existing?.status == TransferStatus.COMPLETE) { notify("This original is already saved."); return }
        if (existing?.status in setOf(TransferStatus.AWAITING_APPROVAL, TransferStatus.QUEUED, TransferStatus.TRANSFERRING)) {
            notify("This request is already in progress. Cancel it before requesting again."); return
        }
        val endpoint = endpointFor(offer.eventId, offer.ownerId)
        val transfer = Transfer(offer.eventId, offer.ownerId, offer.photoId, me,
            requestId = newId(), direction = TransferDirection.RECEIVE,
            status = if (endpoint == null) TransferStatus.WAITING else TransferStatus.AWAITING_APPROVAL,
            displayName = offer.displayName, mimeType = offer.mimeType, size = offer.size, sha256 = existing?.sha256.orEmpty(),
            error = if (endpoint == null) "Waiting for your friend. Open this event on both phones and retry." else null)
        if (!beginTransfer(transfer)) return
        if (endpoint != null && validPeer(endpoint, offer.eventId, offer.ownerId)) {
            try { transport.sendMessage(endpoint, WireMessage.Request(offer.eventId, transfer.requestId, offer.photoId)) }
            catch (error: Exception) {
                changeTransfer(transfer, transfer.copy(status = TransferStatus.WAITING, error = "Friend is offline. Reconnect and retry."))
                throw error
            }
        }
    }

    suspend fun retry(transfer: Transfer) {
        if (transfer.direction == TransferDirection.RECEIVE) {
            val offer = current.offers.find { it.eventId == transfer.eventId && it.ownerId == transfer.ownerId && it.photoId == transfer.photoId }
                ?: PhotoOffer(transfer.eventId, transfer.ownerId, transfer.photoId, transfer.displayName, transfer.mimeType, transfer.size, transfer.sha256)
            request(offer)
        } else {
            check(transfer.approved) { "Approve this request first." }
            approve(transfer)
        }
    }

    private suspend fun receiveRequest(endpoint: String, peer: String, request: WireMessage.Request) {
        if (!current.canAccessPhoto(request.eventId, peer, request.photoId)) {
            transport.sendMessage(endpoint, WireMessage.Decision(request.eventId, request.requestId, request.photoId, false, "This photo is no longer shared."))
            return
        }
        val photo = current.photos.first { it.eventId == request.eventId && it.id == request.photoId }
        val me = requireNotNull(current.profile).id
        val existing = current.transfers.find { it.key == transferKey(request.eventId, me, photo.id, peer) }
        if (existing?.requestId == request.requestId) return
        if (existing?.status in setOf(TransferStatus.QUEUED, TransferStatus.TRANSFERRING)) {
            transport.sendMessage(endpoint, WireMessage.Decision(request.eventId, request.requestId, request.photoId, false, "An earlier transfer is still active. Cancel it on both phones before retrying."))
            return
        }
        if (!beginTransfer(Transfer(request.eventId, me, photo.id, peer, requestId = request.requestId, direction = TransferDirection.SEND,
            status = TransferStatus.AWAITING_APPROVAL, displayName = photo.displayName, mimeType = photo.mimeType, size = photo.size, sha256 = photo.sha256))) return
        notify("${current.peers.find { it.eventId == request.eventId && it.peerId == peer }?.nickname ?: "A friend"} requested an original. Review it in Downloads.")
    }

    suspend fun approve(request: Transfer) {
        val latest = current.transfers.find { it.key == request.key } ?: return
        require(latest.requestId == request.requestId && latest.direction == TransferDirection.SEND && latest.status in setOf(TransferStatus.AWAITING_APPROVAL, TransferStatus.WAITING, TransferStatus.FAILED))
        check(current.canAccessPhoto(latest.eventId, latest.receiverId, latest.photoId)) { "This friend or photo no longer has access." }
        val endpoint = endpointFor(latest.eventId, latest.receiverId)
        if (endpoint == null) {
            changeTransfer(latest, latest.copy(approved = true, status = TransferStatus.WAITING, error = "Waiting for your friend. Reconnect and tap retry."))
            return
        }
        val session = generation
        val photo = current.photos.first { it.eventId == latest.eventId && it.id == latest.photoId }
        var snapshot: File? = null
        var payload: PreparedFile? = null
        var queued: Transfer? = null
        try {
            snapshot = files.outgoingSnapshot(photo)
            withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(photo.uri), "r")?.use { }
                    ?: error("Access to the original was removed.")
            }
            check(session == generation && validPeer(endpoint, latest.eventId, latest.receiverId)) { "Friend disconnected. Retry after reconnecting." }
            check(current.canAccessPhoto(latest.eventId, latest.receiverId, latest.photoId)) { "Access was removed." }
            check(SessionPolicy.sameAttempt(current.transfers.find { it.key == latest.key }, latest)) { "This request changed. Review the latest request." }
            payload = transport.prepareFile(snapshot)
            val readyPayload = payload
            prepared[readyPayload.payloadId] = readyPayload to snapshot
            val transfer = latest.copy(approved = true, status = TransferStatus.QUEUED, payloadId = readyPayload.payloadId, bytesTransferred = 0, error = null)
            check(changeTransfer(latest, transfer)) { "This request changed. Review it again." }
            queued = transfer
            check(session == generation && validPeer(endpoint, latest.eventId, latest.receiverId) && current.canSendOriginal(transfer)) { "Sharing access changed." }
            transport.sendMessage(endpoint, WireMessage.Decision(latest.eventId, latest.requestId, latest.photoId, true))
            check(session == generation && validPeer(endpoint, latest.eventId, latest.receiverId) && SessionPolicy.sameAttempt(current.transfers.find { it.key == transfer.key }, transfer)) { "Sharing access changed." }
            transport.sendMessage(endpoint, WireMessage.FileReady(latest.eventId, latest.requestId, latest.photoId, readyPayload.payloadId, photo.displayName, photo.mimeType, photo.size, photo.sha256))
        } catch (error: Exception) {
            payload?.payloadId?.let(::releasePrepared)
            snapshot?.delete()
            val expected = queued ?: latest
            changeTransfer(expected, expected.copy(status = TransferStatus.FAILED, payloadId = null, error = "Could not read or send the original. Check photo access and retry."))
            throw error
        }
    }

    suspend fun reject(transfer: Transfer) {
        val latest = current.transfers.find { it.key == transfer.key && it.requestId == transfer.requestId } ?: return
        if (latest.direction != TransferDirection.SEND || latest.status != TransferStatus.AWAITING_APPROVAL) return
        if (!changeTransfer(latest, latest.copy(status = TransferStatus.REJECTED, approved = false, error = "Owner declined this request."))) return
        endpointFor(latest.eventId, latest.receiverId)?.let { transport.sendMessage(it, WireMessage.Decision(latest.eventId, latest.requestId, latest.photoId, false, "Owner declined this request.")) }
    }

    suspend fun cancel(transfer: Transfer, sendMessage: Boolean = true) {
        val latest = current.transfers.find { it.key == transfer.key && it.requestId == transfer.requestId } ?: return
        if (latest.status in terminalStates) return
        latest.payloadId?.let { transport.cancel(it); receivingJobs.remove(it)?.cancel(); releasePrepared(it) }
        if (!changeTransfer(latest, latest.copy(status = TransferStatus.CANCELLED, approved = false, payloadId = null, error = "Cancelled. You can request it again."))) return
        val other = if (latest.direction == TransferDirection.SEND) latest.receiverId else latest.ownerId
        if (sendMessage) endpointFor(latest.eventId, other)?.let {
            try { transport.sendMessage(it, WireMessage.Cancel(latest.eventId, latest.requestId, latest.photoId)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notify("Cancelled on this phone. Your friend may need to reconnect to see the change.") }
        }
    }

    private suspend fun receiveMetadata(endpoint: String, peer: String, metadata: WireMessage.FileReady) {
        val transfer = current.transfers.find { it.eventId == metadata.eventId && it.requestId == metadata.requestId && it.photoId == metadata.photoId && it.ownerId == peer && it.direction == TransferDirection.RECEIVE } ?: return
        if (transfer.status == TransferStatus.COMPLETE) {
            transport.sendMessage(endpoint, WireMessage.Receipt(metadata.eventId, transfer.requestId, transfer.photoId, transfer.sha256)); return
        }
        if (transfer.status != TransferStatus.QUEUED || !transfer.approved || transfer.payloadId != null) return
        require(transfer.size == metadata.byteCount && transfer.mimeType == metadata.mimeType)
        require(transfer.sha256.isEmpty() || transfer.sha256 == metadata.sha256)
        val updated = transfer.copy(payloadId = metadata.payloadId, sha256 = metadata.sha256, status = TransferStatus.TRANSFERRING)
        if (!changeTransfer(transfer, updated)) return
        if (!validPeer(endpoint, metadata.eventId, peer) || !SessionPolicy.sameAttempt(current.transfers.find { it.key == transfer.key }, updated)) return
        try {
            transport.expectFile(endpoint, metadata.payloadId, metadata.byteCount)
            transport.sendMessage(endpoint, WireMessage.Ready(metadata.eventId, transfer.requestId, metadata.payloadId))
        } catch (error: Exception) {
            transport.cancel(metadata.payloadId)
            changeTransfer(updated, updated.copy(status = TransferStatus.FAILED, payloadId = null, error = "Download could not start. Reconnect and retry."))
            if (validPeer(endpoint, metadata.eventId, peer)) runCatching {
                transport.sendMessage(endpoint, WireMessage.Error(metadata.eventId, transfer.requestId, "transfer_setup_failed"))
            }
            throw error
        }
    }

    private suspend fun receiveFile(event: TransportEvent.FileReceived) {
        var temporary: File? = null
        var receiving: Transfer? = null
        try {
            val peer = identities[event.endpointId] ?: return
            val transfer = current.transfers.find { it.eventId == eventId.value && it.ownerId == peer && it.payloadId == event.payloadId && it.direction == TransferDirection.RECEIVE && it.status == TransferStatus.TRANSFERRING } ?: return
            receiving = transfer
            val session = generation
            val receiveContext = currentCoroutineContext()
            val cache = File(context.cacheDir, "incoming").apply { mkdirs() }
            val copy = File.createTempFile("received-", ".tmp", cache)
            temporary = copy
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(event.uri)?.use { source -> copy.outputStream().use { digest(source, it) } }
                    ?: error("Received file disappeared.")
            }
            check(session == generation && validPeer(event.endpointId, transfer.eventId, peer)) { "Session ended." }
            check(SessionPolicy.canReceive(current, transfer)) { "This request is no longer active." }
            val saved = files.saveReceived(copy, transfer) {
                receiveContext.isActive && session == generation && eventId.value == transfer.eventId && SessionPolicy.canReceive(current, transfer)
            }
            store.update { SessionPolicy.recordSaved(it, transfer, saved.toString()) }
            if (session == generation && validPeer(event.endpointId, transfer.eventId, peer)) {
                try { transport.sendMessage(event.endpointId, WireMessage.Receipt(transfer.eventId, transfer.requestId, transfer.photoId, transfer.sha256)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notify("Original saved. Reconnect so your friend can receive the completion receipt.") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            receiving?.let { changeTransfer(it, it.copy(status = TransferStatus.FAILED, error = "Original could not be verified or saved. Check space, reconnect, and retry.")) }
        } finally {
            temporary?.delete()
            runCatching { context.contentResolver.delete(event.uri, null, null) }
        }
    }

    private suspend fun updateProgress(event: TransportEvent.Progress) {
        val peer = identities[event.endpointId] ?: return
        val transfer = current.transfers.find { it.eventId == eventId.value && it.payloadId == event.payloadId && (it.ownerId == peer || it.receiverId == peer) } ?: return
        val updated = SessionPolicy.progress(transfer, event.bytesTransferred, event.status)
        if (updated != transfer) changeTransfer(transfer, updated)
        if (event.status != PayloadStatus.IN_PROGRESS) releasePrepared(event.payloadId)
    }

    suspend fun decide(offer: PhotoOffer, decision: MatchDecision) {
        store.update { it.decideMatch(offer.key, decision) }
        endpointFor(offer.eventId, offer.ownerId)?.let { transport.sendMessage(it, WireMessage.MatchDecision(offer.eventId, offer.photoId, decision.name)) }
    }

    suspend fun revoke(event: String, peer: String) {
        val endpoint = endpointFor(event, peer)
        store.update { it.revokePeer(event, peer) }
        endpoint?.let {
            refreshJobs.remove(it)?.cancel()
            runCatching { transport.sendMessage(it, WireMessage.Revoke(event, false)) }
            transport.disconnect(it)
        }
    }

    private suspend fun markDisconnected(event: String, peer: String?, session: Int = generation,
        interrupted: List<Transfer> = current.transfers.filter { it.eventId == event && (peer == null || it.ownerId == peer || it.receiverId == peer) && it.status !in terminalStates }) {
        store.update { SessionPolicy.disconnect(it, event, peer, interrupted, clearReferences = session == generation) }
    }

    private fun validPeer(endpoint: String, event: String, peer: String): Boolean =
        eventId.value == event && identities[endpoint] == peer && endpoint in readyPeers && current.isApprovedPeer(event, peer) &&
            transport.peers.value.any { it.endpointId == endpoint && it.status == PeerStatus.VERIFIED }

    fun endpointFor(event: String, peer: String): String? = identities.entries.find { it.value == peer && validPeer(it.key, event, peer) }?.key

    private suspend fun updateTransfer(request: String, peer: String, photo: String, direction: TransferDirection, transform: (Transfer) -> Transfer) {
        val transfer = current.transfers.find { it.eventId == eventId.value && it.requestId == request && it.photoId == photo && it.direction == direction && (it.ownerId == peer || it.receiverId == peer) } ?: return
        changeTransfer(transfer, transform(transfer))
    }

    private suspend fun beginTransfer(transfer: Transfer): Boolean {
        var changed = false
        store.update { state -> SessionPolicy.beginAttempt(state, transfer).also { changed = it != state } }
        return changed
    }

    private suspend fun changeTransfer(expected: Transfer, next: Transfer): Boolean {
        var changed = false
        store.update { state -> SessionPolicy.updateAttempt(state, expected, next).also { changed = it != state } }
        return changed
    }
    private fun releasePrepared(id: Long) { prepared.remove(id)?.let { (payload, file) -> runCatching { payload.close() }; file.delete() } }

    companion object {
        const val FACE_MODEL = "opencv-sface-2021dec-128"
        private val terminalStates = SessionPolicy.terminal
    }
}
