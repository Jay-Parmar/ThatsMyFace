package com.thatsmyface

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thatsmyface.data.*
import com.thatsmyface.recognition.FaceEngine
import com.thatsmyface.recognition.FaceMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppModel @JvmOverloads constructor(application: Application, val store: LocalStore = LocalStore(application)) : AndroidViewModel(application) {
    val files = PhotoFiles(application)
    private val engine = FaceEngine(application)
    val sharing = SharingSession(application, store, files, engine, viewModelScope, ::notify)
    val nearbyPeers = sharing.transport.peers
    val sharingActive = sharing.transport.active
    val sharingEventId = sharing.eventId
    val state = store.state
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    val loadFailed = MutableStateFlow(false)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    val selectedEventId = MutableStateFlow<String?>(null)
    val scanSummary = MutableStateFlow<String?>(null)

    init {
        runAction {
            try { store.load() }
            catch (error: Exception) { loadFailed.value = true; throw error }
            selectedEventId.value = state.value.events.firstOrNull()?.id
            _ready.value = true
        }
        viewModelScope.launch {
            selectedEventId.collect { selected ->
                scanSummary.value = null
                if (sharing.eventId.value != null && sharing.eventId.value != selected) sharing.stop()
            }
        }
    }

    fun dismissMessage() { _message.value = null }
    fun notify(message: String) { _message.value = message }

    fun runAction(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.message?.take(200) ?: "Something went wrong. Please try again."
            } finally {
                _busy.value = false
            }
        }
    }

    fun saveProfile(nickname: String) = runAction {
        require(nickname.trim().length in 1..32) { "Use a nickname of 1 to 32 characters." }
        store.update { it.copy(profile = it.profile?.copy(nickname = nickname.trim()) ?: Profile(nickname = nickname.trim())) }
    }

    fun createEvent(title: String) = runAction {
        val event = Invitations.create(title)
        store.update { it.copy(events = it.events + event) }
        selectedEventId.value = event.id
    }

    fun joinEvent(invitation: String) = runAction {
        val event = Invitations.decode(invitation.trim())
        store.update {
            val existing = it.events.find { entry -> entry.id == event.id }
            require(existing == null || existing.secret == event.secret) { "This invitation conflicts with the saved event." }
            if (existing == null) it.copy(events = it.events + event) else it
        }
        selectedEventId.value = event.id
    }

    fun importPhotos(uris: List<Uri>) = importPhotos(selectedEventId.value, uris)

    fun importPhotos(eventId: String?, uris: List<Uri>) = runAction {
        importIntoEvent(requireEvent(eventId).id, uris, true)
    }

    internal suspend fun importIntoEvent(eventId: String, uris: List<Uri>, persist: Boolean) {
        requireEvent(eventId)
        var added = 0
        var failed = 0
        var firstFailure: String? = null
        require(uris.size <= 2000) { "Choose at most 2,000 photos at a time." }
        for (uri in uris) {
            try {
                val photo = files.importPhoto(eventId, uri, persist)
                val replacedIds = state.value.photos.filter { it.eventId == eventId && it.uri == photo.uri && it.sha256 != photo.sha256 }.map { it.id }.toSet()
                state.value.transfers.filter {
                    it.eventId == eventId && it.ownerId == state.value.profile?.id && it.photoId in replacedIds && it.status != TransferStatus.COMPLETE
                }.forEach { sharing.cancel(it) }
                store.update { current ->
                    if (current.photos.any { it.eventId == eventId && it.uri == photo.uri && it.sha256 == photo.sha256 }) {
                        current.copy(photos = current.photos.map {
                            if (it.eventId == eventId && it.uri == photo.uri && it.sha256 == photo.sha256) it.copy(availability = PhotoAvailability.AVAILABLE, error = null) else it
                        })
                    }
                    else {
                        added++
                        current.copy(
                            photos = current.photos.filterNot { it.eventId == eventId && it.id in replacedIds } + photo,
                            offers = current.offers.filterNot { it.eventId == eventId && it.ownerId == current.profile?.id && it.photoId in replacedIds },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LocalDataException) {
                throw error
            } catch (error: Exception) {
                failed++
                if (firstFailure == null) firstFailure = error.message?.take(150) ?: "Check photo access and try again."
            }
        }
        notify("Added $added photos." + if (failed > 0) " $failed could not be read. $firstFailure" else "")
        sharing.refreshAll()
    }

    fun selectFolder(uri: Uri) = selectFolder(selectedEventId.value, uri)

    fun selectFolder(eventId: String?, uri: Uri) = runAction {
        val event = requireEvent(eventId)
        val selected = files.listFolder(uri)
        store.update { current -> current.copy(events = current.events.map { if (it.id == eventId) it.copy(folderUri = uri.toString()) else it }) }
        importIntoEvent(event.id, selected, false)
    }

    fun rescan() = runAction {
        val event = requireEvent(selectedEventId.value)
        val checked = state.value.photos.filter { it.eventId == event.id }.map { files.checkAvailability(it) }.associateBy { it.id }
        val unavailable = checked.values.filter { it.availability != PhotoAvailability.AVAILABLE }.map { it.id }.toSet()
        state.value.transfers.filter {
            it.eventId == event.id && it.ownerId == state.value.profile?.id && it.photoId in unavailable && it.status != TransferStatus.COMPLETE
        }.forEach { sharing.cancel(it) }
        store.update { it.copy(
            photos = it.photos.map { photo -> if (photo.eventId == event.id) checked[photo.id] ?: photo else photo },
            offers = it.offers.filterNot { offer -> offer.eventId == event.id && offer.ownerId == it.profile?.id && offer.photoId in unavailable },
        ) }
        sharing.refreshAll()
        event.folderUri?.let { importIntoEvent(event.id, files.listFolder(Uri.parse(it)), false) }
        notify("Selected photos checked. Only the chosen event folder was rescanned.")
        sharing.refreshAll()
    }

    fun toggleTag(photo: Photo, participantId: String) = runAction {
        val selected = state.value.photos.find { it.eventId == photo.eventId && it.id == photo.id }
            ?: error("This photo is no longer selected for the event.")
        val ids = if (participantId in selected.manualPersonIds) selected.manualPersonIds - participantId else selected.manualPersonIds + participantId
        store.update { it.tagPhoto(selected.eventId, selected.id, ids) }
        if (participantId == state.value.profile?.id) {
            val preview = try { files.thumbnail(selected) }
            catch (error: PhotoAccessException) { markUnavailable(selected, error); sharing.refreshAll(); throw error }
            val owner = requireNotNull(state.value.profile).id
            store.update { current ->
                val offer = PhotoOffer(selected.eventId, owner, selected.id, selected.displayName, selected.mimeType, selected.size, selected.sha256,
                    preview, if (participantId in ids) MatchKind.MANUAL else MatchKind.AVAILABLE,
                    if (participantId in ids) MatchDecision.CONFIRMED else MatchDecision.NONE)
                current.copy(offers = current.offers.filterNot { it.key == offer.key } + offer)
            }
        }
        sharing.refreshAll()
    }

    fun removePhoto(photo: Photo) = runAction {
        state.value.transfers.filter { it.eventId == photo.eventId && it.ownerId == state.value.profile?.id && it.photoId == photo.id && it.status != TransferStatus.COMPLETE }.forEach { sharing.cancel(it) }
        store.update { it.copy(
            photos = it.photos.filterNot { entry -> entry.eventId == photo.eventId && entry.id == photo.id },
            offers = it.offers.filterNot { offer -> offer.eventId == photo.eventId && offer.photoId == photo.id && offer.ownerId == it.profile?.id },
        ) }
        sharing.refreshAll()
        notify("Removed from this event. The original stays on your phone.")
    }

    fun deleteFaceData() = runAction {
        sharing.stop()
        store.clearFaceData()
        notify("Sharing stopped. Local face data deleted and future reference sharing switched off.")
    }

    fun deleteLocalData() = runAction {
        sharing.stop()
        store.clearAll()
        files.releasePermissions()
        withContext(Dispatchers.IO) {
            listOf("outgoing", "incoming").forEach { name -> java.io.File(getApplication<Application>().cacheDir, name).deleteRecursively() }
        }
        selectedEventId.value = null
        scanSummary.value = null
        _ready.value = true
        loadFailed.value = false
        notify("Local app data deleted. Source photos and downloaded copies remain.")
    }

    fun saveFeedback(note: String) = runAction {
        require(note.trim().length in 1..1000) { "Write a short note, up to 1000 characters." }
        store.update { it.copy(feedback = it.feedback + Feedback(note = note.trim(), version = BuildConfig.VERSION_NAME)) }
        notify("Feedback saved on this phone.")
    }

    fun exportFeedback(uri: Uri) = runAction {
        require(uri.scheme == "content") { "Choose a feedback destination using the Android file picker." }
        val text = state.value.feedback.joinToString("\n\n") { "ThatsMyFace ${it.version}\n${it.note}" }
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                ?: error("Could not open the selected destination.")
        }
        notify("Feedback exported. Only notes and app versions were included.")
    }

    fun enroll(uris: List<Uri>) = runAction {
        require(uris.size in 1..5) { "Choose one to five reference selfies." }
        val references = uris.map { uri ->
            val faces = engine.extract(uri)
            require(faces.size == 1 && faces.single().suitableForEnrollment) {
                "Each selfie needs one clear, well-lit face looking forward. Try another photo. Nothing was changed."
            }
            faces.single().embedding.toList()
        }
        store.update { it.copy(profile = requireNotNull(it.profile).copy(faceRefs = references)) }
        sharing.sendReferences()
        sharing.refreshAll()
        notify("${references.size} face references saved locally. Selfie images were not copied. Enable sharing for an event when you're ready.")
    }

    fun setFaceSharing(eventId: String, enabled: Boolean) = runAction {
        require(!enabled || state.value.profile?.faceRefs?.isNotEmpty() == true) { "Choose reference selfies in You first." }
        store.update { it.copy(events = it.events.map { e -> if (e.id == eventId) e.copy(shareFaceData = enabled) else e }) }
        sharing.sendReferences()
        sharing.refreshAll()
    }

    fun scanFaces() = runAction {
        val eventId = requireEvent(selectedEventId.value).id
        val profile = requireNotNull(state.value.profile)
        require(profile.faceRefs.isNotEmpty()) { "Add reference selfies in You to find yourself." }
        val participants = state.value.peers.filter { it.eventId == eventId && it.allowed && it.faceRefs.isNotEmpty() }
            .associate { it.peerId to it.faceRefs.map { vector -> vector.toFloatArray() } } +
            (profile.id to profile.faceRefs.map { it.toFloatArray() })
        var suggested = 0
        var uncertain = 0
        var failed = 0
        var facesSeen = 0
        for (photo in state.value.photos.filter { it.eventId == eventId && it.availability == PhotoAvailability.AVAILABLE }) {
            try {
                val faces = engine.extract(Uri.parse(photo.uri))
                facesSeen += faces.size
                val matches = faces.map { FaceMatcher.match(it.embedding, participants, it.issues.isEmpty()) }.filter { it.participantId == profile.id }
                val kind = when {
                    profile.id in photo.manualPersonIds -> MatchKind.MANUAL
                    matches.any { it.kind == com.thatsmyface.recognition.MatchKind.SUGGESTED } -> MatchKind.SUGGESTED.also { suggested++ }
                    matches.isNotEmpty() -> MatchKind.UNCERTAIN.also { uncertain++ }
                    else -> MatchKind.AVAILABLE
                }
                val preview = files.thumbnail(photo)
                store.update { current ->
                    val previous = current.offers.find { it.eventId == eventId && it.ownerId == profile.id && it.photoId == photo.id }
                    val offer = PhotoOffer(eventId, profile.id, photo.id, photo.displayName, photo.mimeType, photo.size, photo.sha256,
                        preview, kind, previous?.decision ?: MatchDecision.NONE)
                    current.copy(offers = current.offers.filterNot { it.key == offer.key } + offer)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: PhotoAccessException) { failed++; markUnavailable(photo, error) }
            catch (error: LocalDataException) { throw error }
            catch (_: Exception) {
                failed++
                store.update { current -> current.copy(offers = current.offers.filterNot {
                    it.eventId == eventId && it.ownerId == profile.id && it.photoId == photo.id
                }) }
            }
        }
        if (selectedEventId.value == eventId) scanSummary.value = "$facesSeen faces checked. $suggested suggestions, $uncertain uncertain photos." + if (failed > 0) " $failed photos could not be processed." else ""
        sharing.refreshAll()
    }

    private fun requireEvent(eventId: String?): Event = state.value.events.find { it.id == eventId }
        ?: error("This event is no longer selected. Choose an event and select the photos again.")

    private suspend fun markUnavailable(photo: Photo, error: PhotoAccessException) {
        store.update { current -> current.copy(
            photos = current.photos.map { if (it.eventId == photo.eventId && it.id == photo.id) it.copy(availability = error.availability, error = error.message) else it },
            offers = current.offers.filterNot { it.eventId == photo.eventId && it.ownerId == current.profile?.id && it.photoId == photo.id },
        ) }
    }

    fun startSharing() = runAction { sharing.start(requireEvent(selectedEventId.value)) }
    fun stopSharing() { sharing.stop() }
    fun connect(endpointId: String) = runAction { sharing.transport.connect(endpointId) }
    fun verify(endpointId: String) = runAction { sharing.transport.verify(endpointId) }
    fun rejectPeer(endpointId: String) { sharing.transport.reject(endpointId) }
    fun revokePeer(peer: Peer) = runAction { sharing.revoke(peer.eventId, peer.peerId) }
    fun refreshFriends() { sharing.refreshAll() }
    fun requestPhoto(offer: PhotoOffer) = runAction { sharing.request(offer) }
    fun checkSavedPhoto(transfer: Transfer, open: ((Uri) -> Unit)? = null) = runAction {
        val checked = sharing.checkSavedCopy(transfer) ?: return@runAction
        if (checked.availability == SavedCopyAvailability.AVAILABLE) {
            if (open == null) notify("Your saved original is available and unchanged.")
            else state.value.transfers.find { it.key == transfer.key && it.requestId == transfer.requestId }?.savedUri?.let { open(Uri.parse(it)) }
        } else notify(checked.message ?: "This saved copy could not be checked.")
    }
    fun decideMatch(offer: PhotoOffer, decision: MatchDecision) = runAction { sharing.decide(offer, decision) }
    fun approveTransfer(transfer: Transfer) = runAction { sharing.approve(transfer) }
    fun rejectTransfer(transfer: Transfer) = runAction { sharing.reject(transfer) }
    fun retryTransfer(transfer: Transfer) = runAction { sharing.retry(transfer) }
    fun cancelTransfer(transfer: Transfer) = runAction { sharing.cancel(transfer) }

    override fun onCleared() {
        sharing.stop()
        super.onCleared()
    }
}
