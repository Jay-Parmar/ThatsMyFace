package com.thatsmyface.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thatsmyface.AppModel
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.data.MatchDecision
import com.thatsmyface.data.MatchKind
import com.thatsmyface.data.PhotoOffer
import com.thatsmyface.data.PhotoAvailability
import com.thatsmyface.data.Transfer
import com.thatsmyface.data.TransferDirection
import com.thatsmyface.data.TransferStatus
import com.thatsmyface.data.SavedCopyAvailability
import com.thatsmyface.data.transferKey
import com.thatsmyface.nearby.PeerStatus

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PhotosPage(model: AppModel, state: AppState, event: Event, busy: Boolean,
    openFriends: () -> Unit, openProfile: () -> Unit) {
    var selected by rememberSaveable(event.id) { mutableIntStateOf(0) }
    var focusedTransfer by rememberSaveable(event.id) { mutableStateOf<String?>(null) }
    val pages = rememberSaveableStateHolder()
    val approvals = state.transfers.count { it.eventId == event.id && it.needsOwnerDecision() }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("On my phone", "Of me", "Requested").forEachIndexed { index, title ->
                FilterChip(selected = index == selected, onClick = { selected = index },
                    label = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, maxLines = 1, softWrap = false)
                        if (index == 2 && approvals > 0) Badge { Text(approvals.toString()) }
                    } }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (approvals > 0 && selected != 2) Button(onClick = { selected = 2 },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
            Text(if (approvals == 1) "1 request needs your approval" else "$approvals requests need your approval")
        }
        Box(Modifier.weight(1f)) {
            pages.SaveableStateProvider("${event.id}:$selected") {
                when (selected) {
                    0 -> LocalPhotosPage(model, state, event, busy)
                    1 -> FoundPhotos(model, state, event, busy, openFriends, openProfile, openRequested = {
                        focusedTransfer = it
                        selected = 2
                    })
                    else -> TransfersPage(model, state, event, busy, focusedTransfer, onFocusConsumed = { focusedTransfer = null })
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FoundPhotos(model: AppModel, state: AppState, event: Event, busy: Boolean,
    openFriends: () -> Unit, openProfile: () -> Unit, openRequested: (String) -> Unit) {
    var filter by rememberSaveable(event.id) { mutableIntStateOf(0) }
    var query by rememberSaveable(event.id) { mutableStateOf("") }
    var searching by rememberSaveable(event.id) { mutableStateOf(false) }
    var showScanDetails by rememberSaveable(event.id) { mutableStateOf(false) }
    var selectedOffer by rememberSaveable(event.id) { mutableStateOf<String?>(null) }
    val summary by model.scanSummary.collectAsStateWithLifecycle()
    val nearby by model.nearbyPeers.collectAsStateWithLifecycle()
    val active by model.sharingActive.collectAsStateWithLifecycle()
    val sessionEvent by model.sharingEventId.collectAsStateWithLifecycle()
    val sharing = active && sessionEvent == event.id
    val connectedFriends = if (sharing) nearby.count { it.status == PeerStatus.VERIFIED } else 0
    val eventOffers = state.offers.filter { it.eventId == event.id }
    val localPhotos = state.photos.count { it.eventId == event.id && it.availability == PhotoAvailability.AVAILABLE }
    val enrolled = !state.profile?.faceRefs.isNullOrEmpty()
    val receivedCount = eventOffers.count { it.ownerId != state.profile?.id }
    val suggested = eventOffers.filter {
        it.decision == MatchDecision.CONFIRMED || (it.match != MatchKind.AVAILABLE && it.decision != MatchDecision.REJECTED)
    }
    val uncertain = eventOffers.filter { it.match == MatchKind.UNCERTAIN && it.decision == MatchDecision.NONE }
    val confirmed = eventOffers.filter { it.decision == MatchDecision.CONFIRMED }
    val filtered = when (filter) { 1 -> uncertain; 2 -> confirmed; 3 -> eventOffers; else -> suggested }
    val offers = filtered.filter { it.displayName.contains(query.trim(), ignoreCase = true) ||
        ownerName(state, event, it.ownerId).contains(query.trim(), ignoreCase = true) }
    PhotoGrid {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeading("Find your moments", "$receivedCount ${if (receivedCount == 1) "preview" else "previews"} from friends · " +
                    "$connectedFriends ${if (connectedFriends == 1) "friend" else "friends"} connected")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!sharing || connectedFriends == 0) TextButton(onClick = openFriends,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (sharing) "Finish pairing" else "Connect with friends") }
                    if (eventOffers.isNotEmpty()) TextButton(onClick = { searching = !searching; if (!searching) query = "" },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (searching) "Close search" else "Search") }
                    if (eventOffers.isNotEmpty() && localPhotos > 0) TextButton(
                        onClick = if (enrolled) model::scanFaces else openProfile, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (enrolled) "Find me in my photos" else "Set up face matching") }
                    if (summary != null) TextButton(onClick = { showScanDetails = !showScanDetails },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showScanDetails) "Hide scan details" else "Scan details") }
                }
                if (searching) SearchPhotos(query, { query = it }, "Search photo names or friends")
                if (showScanDetails) summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("For you ${suggested.size}", "Uncertain ${uncertain.size}", "Confirmed ${confirmed.size}", "All previews ${eventOffers.size}").forEachIndexed { index, label ->
                        FilterChip(selected = filter == index, onClick = { filter = index },
                            label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }
        if (offers.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                query.isNotBlank() -> Panel("No photos found", "Try another photo name or friend.") {
                    TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear search") }
                }
                eventOffers.isEmpty() -> Panel("Your moments will appear here", when {
                    localPhotos > 0 && enrolled -> "Your selected photos are ready. Run matching here to look for yourself on this phone."
                    localPhotos > 0 -> "Add a few reference selfies to find yourself in your selected photos, or connect with friends to browse their previews."
                    connectedFriends > 0 -> "Ask your friend to select event photos or use Resend my event photos in Friends."
                    else -> "Select photos in On my phone, or connect with friends who have selected theirs. Only verified event friends can exchange previews."
                }) {
                    if (localPhotos > 0) Button(onClick = if (enrolled) model::scanFaces else openProfile, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (enrolled) "Find me in my photos" else "Set up face matching") }
                }
                else -> Panel("No photos in this view", "Explore all event previews to find missed moments, then mark the photos you're in.") {
                    Button(onClick = { filter = 3 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show all event previews") }
                    if (state.profile?.faceRefs.isNullOrEmpty()) TextButton(onClick = openProfile,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Set up face matching") }
                }
            }
        }
        items(offers, key = { it.key }) { offer ->
            PhotoTile(offer.displayName, matchLabel(offer), ownerName(state, event, offer.ownerId),
                onClick = { selectedOffer = offer.key }) {
                OfferPreview(offer.thumbnailBase64, it)
            }
        }
    }
    selectedOffer?.let { key -> eventOffers.find { it.key == key }?.let { offer ->
        PhotoDetails(offer.displayName, onDismiss = { selectedOffer = null }) {
            OfferPreview(offer.thumbnailBase64, Modifier.fillMaxWidth().height(280.dp), detailed = true)
            Text("${matchLabel(offer)} · ${ownerName(state, event, offer.ownerId)}", color = MaterialTheme.colorScheme.secondary)
            Text("Suggestions can be wrong. Confirm whether you are in this photo.")
            OutlinedButton(onClick = { model.decideMatch(offer, MatchDecision.CONFIRMED) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("That's me") }
            TextButton(onClick = { model.decideMatch(offer, MatchDecision.REJECTED) }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Not me") }
            if (offer.ownerId == state.profile?.id) Text("You already have this original.", style = MaterialTheme.typography.bodySmall)
            else {
                val download = state.transfers.find { it.eventId == event.id && it.ownerId == offer.ownerId &&
                    it.photoId == offer.photoId && it.direction == TransferDirection.RECEIVE }
                Text("The owner must be connected and approve the original. Track it in Requested.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (download == null || download.status in setOf(TransferStatus.FAILED, TransferStatus.CANCELLED,
                            TransferStatus.REJECTED, TransferStatus.WAITING) || download.savedCopyAvailability == SavedCopyAvailability.MISSING) {
                        model.requestPhoto(offer)
                    }
                    selectedOffer = null
                    state.profile?.let { openRequested(transferKey(event.id, offer.ownerId, offer.photoId, it.id)) }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when (download?.status) {
                        TransferStatus.COMPLETE -> if (download.savedCopyAvailability == SavedCopyAvailability.MISSING) "Request original again" else "View saved original"
                        TransferStatus.AWAITING_APPROVAL, TransferStatus.QUEUED, TransferStatus.TRANSFERRING -> "View request"
                        TransferStatus.WAITING -> "Retry when friend is nearby"
                        else -> "Request original"
                    })
                }
                download?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    } }
}

private fun matchLabel(offer: PhotoOffer): String = when {
    offer.decision == MatchDecision.CONFIRMED -> "Confirmed by you"
    offer.decision == MatchDecision.REJECTED -> "Not you"
    offer.match == MatchKind.SUGGESTED -> "Suggested"
    offer.match == MatchKind.UNCERTAIN -> "Please check"
    offer.match == MatchKind.MANUAL -> "Tagged as you"
    else -> "Event preview"
}

private fun ownerName(state: AppState, event: Event, ownerId: String): String =
    if (ownerId == state.profile?.id) "Your phone"
    else state.peers.find { it.peerId == ownerId && it.eventId == event.id }?.nickname ?: "Friend"

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TransfersPage(model: AppModel, state: AppState, event: Event, busy: Boolean,
    focusedTransfer: String?, onFocusConsumed: () -> Unit) {
    var selectedTransfer by rememberSaveable(event.id) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(event.id) { mutableIntStateOf(0) }
    var showSent by rememberSaveable(event.id) { mutableStateOf(false) }
    var query by rememberSaveable(event.id) { mutableStateOf("") }
    var searching by rememberSaveable(event.id) { mutableStateOf(false) }
    var showFolder by rememberSaveable(event.id) { mutableStateOf(false) }
    val transfers = state.transfers.filter { it.eventId == event.id }
    LaunchedEffect(focusedTransfer, transfers) {
        if (focusedTransfer != null && transfers.any { it.key == focusedTransfer }) {
            selectedTransfer = focusedTransfer
            onFocusConsumed()
        }
    }
    val approvals = transfers.filter { it.needsOwnerDecision() }
    val received = transfers.filter { it.direction == TransferDirection.RECEIVE }
    val sent = transfers.filter { it.direction == TransferDirection.SEND && !it.needsOwnerDecision() }
    val sentHistory = sent.filter { it.status in setOf(TransferStatus.COMPLETE, TransferStatus.CANCELLED, TransferStatus.REJECTED) }
    val activeSent = sent.filter { it.status !in setOf(TransferStatus.COMPLETE, TransferStatus.CANCELLED, TransferStatus.REJECTED) }
    val completedCount = received.count { it.status == TransferStatus.COMPLETE }
    val visible = received.filter { when (filter) {
        1 -> it.status == TransferStatus.COMPLETE
        2 -> it.status != TransferStatus.COMPLETE
        else -> true
    } }.filter { it.displayName.contains(query.trim(), ignoreCase = true) ||
        ownerName(state, event, it.ownerId).contains(query.trim(), ignoreCase = true)
    }.sortedBy { it.status == TransferStatus.COMPLETE }
    PhotoGrid {
        if (approvals.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Panel("Needs your approval", "${approvals.size} ${if (approvals.size == 1) "request" else "requests"} from friends. " +
                "Approve only originals you want to share, including any location metadata.")
        }
        items(approvals, key = { "approval:${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { transfer ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TransferPreview(model, state, transfer, Modifier.size(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ownerName(state, event, transfer.receiverId), style = MaterialTheme.typography.titleMedium)
                            Text(transfer.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text(transferStatus(transfer), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.approveTransfer(transfer) }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Approve original") }
                        TextButton(onClick = { model.rejectTransfer(transfer) }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Decline request") }
                        TextButton(onClick = { selectedTransfer = transfer.key },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("View photo") }
                    }
                }
            }
        }
        if (activeSent.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sharing with friends", style = MaterialTheme.typography.titleLarge)
                Text("${activeSent.size} active ${if (activeSent.size == 1) "transfer" else "transfers"}. Tap a photo for progress, retry, or cancellation.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(activeSent, key = { "active:${it.key}" }) { transfer ->
            TransferTile(model, state, event, transfer) { selectedTransfer = transfer.key }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val pendingCount = received.size - completedCount
                SectionHeading("Your requested photos", "$completedCount received · $pendingCount ${if (pendingCount == 1) "request" else "requests"}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (received.isNotEmpty()) TextButton(onClick = { searching = !searching; if (!searching) query = "" },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (searching) "Close search" else "Search") }
                    TextButton(onClick = { showFolder = !showFolder }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (showFolder) "Hide folder details" else "Where are photos saved?")
                    }
                }
                if (searching) SearchPhotos(query, { query = it }, "Search photo names or friends")
                if (showFolder) Text("New originals appear in your gallery's Requested album, in Pictures/ThatsMyFace/Requested. Earlier downloads stay in their original folder.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All ${received.size}", "Received $completedCount", "Requests ${received.size - completedCount}").forEachIndexed { index, label ->
                        FilterChip(selected = filter == index, onClick = { filter = index },
                            label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }
        if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Panel(if (received.isEmpty()) "Keep your favourite moments" else "No photos in this view",
                if (received.isEmpty()) "Open a photo in Of me and request its original. It appears here while you wait for approval, and stays here after it is saved."
                else "Try another filter to see your received photos and requests.")
        }
        items(visible, key = { "received:${it.key}" }) { transfer ->
            TransferTile(model, state, event, transfer) { selectedTransfer = transfer.key }
        }
        if (sentHistory.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            TextButton(onClick = { showSent = !showSent }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showSent) "Hide sharing history" else "Sharing history (${sentHistory.size})")
            }
        }
        if (showSent) items(sentHistory, key = { "sent:${it.key}" }) { transfer ->
            TransferTile(model, state, event, transfer) { selectedTransfer = transfer.key }
        }
    }
    selectedTransfer?.let { key -> transfers.find { it.key == key }?.let { transfer ->
        PhotoDetails(transfer.displayName, onDismiss = { selectedTransfer = null }) {
            TransferPreview(model, state, transfer, Modifier.fillMaxWidth().height(280.dp), detailed = true)
            TransferActions(model, state, transfer, busy)
        }
    } }
}

@Composable
private fun TransferTile(model: AppModel, state: AppState, event: Event, transfer: Transfer, onClick: () -> Unit) {
    val otherId = if (transfer.direction == TransferDirection.SEND) transfer.receiverId else transfer.ownerId
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PhotoTile(transfer.displayName, transferStatus(transfer), ownerName(state, event, otherId), onClick) {
            TransferPreview(model, state, transfer, it)
        }
        if (transfer.status in setOf(TransferStatus.QUEUED, TransferStatus.TRANSFERRING)) {
            LinearProgressIndicator(progress = { transferProgress(transfer) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TransferPreview(model: AppModel, state: AppState, transfer: Transfer, modifier: Modifier, detailed: Boolean = false) {
    if (transfer.direction == TransferDirection.RECEIVE && transfer.status == TransferStatus.COMPLETE) {
        SavedPhotoPreview(model, transfer, modifier, detailed)
    } else {
        val photo = if (transfer.direction == TransferDirection.SEND) state.photos.find {
            it.eventId == transfer.eventId && it.id == transfer.photoId
        } else null
        if (photo != null) LocalPhotoPreview(model, photo, modifier, detailed)
        else OfferPreview(state.offers.find { it.eventId == transfer.eventId &&
            it.ownerId == transfer.ownerId && it.photoId == transfer.photoId }?.thumbnailBase64, modifier, detailed)
    }
}

internal fun Transfer.needsOwnerDecision(): Boolean = direction == TransferDirection.SEND &&
    (status == TransferStatus.AWAITING_APPROVAL || (!approved && status in setOf(TransferStatus.WAITING, TransferStatus.FAILED)))

private fun transferProgress(transfer: Transfer): Float =
    (transfer.bytesTransferred.toFloat() / transfer.size.coerceAtLeast(1)).coerceIn(0f, 1f)

private fun transferStatus(transfer: Transfer): String {
    val sending = transfer.direction == TransferDirection.SEND
    return when (transfer.status) {
        TransferStatus.WAITING -> "Waiting for your friend's phone"
        TransferStatus.AWAITING_APPROVAL -> if (sending) "Your approval is needed" else "Awaiting owner approval"
        TransferStatus.QUEUED -> "Queued"
        TransferStatus.TRANSFERRING -> if (sending) "Sending original" else "Downloading original"
        TransferStatus.COMPLETE -> if (sending) "Original delivered" else when (transfer.savedCopyAvailability) {
            SavedCopyAvailability.UNCHECKED -> "Received; not rechecked"
            SavedCopyAvailability.AVAILABLE -> "Original saved"
            SavedCopyAvailability.MISSING -> "Saved copy is missing"
            SavedCopyAvailability.UNREADABLE -> "Saved copy could not be checked"
            SavedCopyAvailability.CHANGED -> "Saved copy changed"
        }
        TransferStatus.FAILED -> "Transfer failed"
        TransferStatus.REJECTED -> "Request declined"
        TransferStatus.CANCELLED -> "Cancelled"
    }
}

@Composable
private fun TransferActions(model: AppModel, state: AppState, transfer: Transfer, busy: Boolean) {
    val context = LocalContext.current
    val sending = transfer.direction == TransferDirection.SEND
    val otherId = if (sending) transfer.receiverId else transfer.ownerId
    val friend = state.peers.find { it.peerId == otherId && it.eventId == transfer.eventId }?.nickname ?: "Friend"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${transferStatus(transfer)} · ${if (sending) "To" else "From"} $friend", color = MaterialTheme.colorScheme.secondary)
        if (transfer.status == TransferStatus.AWAITING_APPROVAL && !sending) {
            Text("Ask $friend to open Photos → Requested and tap Approve original on their phone. Keep both apps open.",
                style = MaterialTheme.typography.bodyMedium)
        } else if (transfer.status == TransferStatus.WAITING) {
            Text("Reconnect in Friends on both phones and compare the codes again. Retry if this request is still waiting.",
                style = MaterialTheme.typography.bodyMedium)
        }
        transfer.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (transfer.status in setOf(TransferStatus.QUEUED, TransferStatus.TRANSFERRING)) {
            LinearProgressIndicator(progress = { transferProgress(transfer) },
                modifier = Modifier.fillMaxWidth())
            Text("${transfer.bytesTransferred / 1024} KB of ${transfer.size / 1024} KB", style = MaterialTheme.typography.bodySmall)
        }
        if (sending && (transfer.status == TransferStatus.AWAITING_APPROVAL ||
            (transfer.status == TransferStatus.FAILED && !transfer.approved))) {
            Button(onClick = { model.approveTransfer(transfer) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Approve original") }
            OutlinedButton(onClick = { model.rejectTransfer(transfer) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Decline request") }
        }
        if (transfer.status in setOf(TransferStatus.WAITING, TransferStatus.FAILED, TransferStatus.CANCELLED, TransferStatus.REJECTED) &&
            (!sending || transfer.approved)) {
            OutlinedButton(onClick = { model.retryTransfer(transfer) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Retry after reconnecting") }
        }
        if (sending && transfer.status == TransferStatus.WAITING && !transfer.approved) {
            OutlinedButton(onClick = { model.approveTransfer(transfer) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Approve when friend returns") }
        }
        if (transfer.status !in setOf(TransferStatus.COMPLETE, TransferStatus.CANCELLED, TransferStatus.REJECTED)) {
            TextButton(onClick = { model.cancelTransfer(transfer) }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel request") }
        }
        if (transfer.status == TransferStatus.COMPLETE && !sending) {
            OutlinedButton(onClick = { model.checkSavedPhoto(transfer) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Check saved copy") }
            if (transfer.savedCopyAvailability == SavedCopyAvailability.MISSING) {
                Button(onClick = { model.retryTransfer(transfer) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Request original again") }
            }
            if (transfer.savedUri != null && transfer.savedCopyAvailability in setOf(SavedCopyAvailability.AVAILABLE, SavedCopyAvailability.UNCHECKED)) {
                Button(onClick = {
                    model.checkSavedPhoto(transfer) { uri ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, transfer.mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: ActivityNotFoundException) { model.notify("No photo viewer is available. Look for the Requested album in your gallery; earlier copies may be in ThatsMyFace.") }
                        catch (_: SecurityException) { model.notify("The viewer could not access this copy. Check photo access and try again.") }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open saved photo") }
            }
        }
    }
}
