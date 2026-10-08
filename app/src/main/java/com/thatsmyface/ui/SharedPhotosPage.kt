package com.thatsmyface.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thatsmyface.AppModel
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.data.MatchDecision
import com.thatsmyface.data.MatchKind
import com.thatsmyface.data.PhotoOffer
import com.thatsmyface.data.Transfer
import com.thatsmyface.data.TransferDirection
import com.thatsmyface.data.TransferStatus
import com.thatsmyface.data.SavedCopyAvailability

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PhotosPage(model: AppModel, state: AppState, event: Event, busy: Boolean) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("On my phone", "Of me", "Downloads").forEachIndexed { index, title ->
                FilterChip(selected = index == selected, onClick = { selected = index },
                    label = { Text(title, maxLines = 1, softWrap = false) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> LocalPhotosPage(model, state, event, busy)
                1 -> FoundPhotos(model, state, event, busy)
                else -> TransfersPage(model, state, event, busy)
            }
        }
    }
}

@Composable
private fun FoundPhotos(model: AppModel, state: AppState, event: Event, busy: Boolean) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val summary by model.scanSummary.collectAsStateWithLifecycle()
    val offers = state.offers.filter { it.eventId == event.id }.filter {
        showAll || it.decision == MatchDecision.CONFIRMED || (it.match != MatchKind.AVAILABLE && it.decision != MatchDecision.REJECTED)
    }
    Page {
        item { Panel("Is that you?", "Face matching suggests photos, including uncertain ones. Confirm what is right and correct what is wrong. No match is also a normal result.") {
            Text("Friends' phones search their selected photos when you connect and share face references in You.")
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(showAll, role = Role.Switch,
                onValueChange = { showAll = it }), verticalAlignment = Alignment.CenterVertically) {
                Text("Show all event previews", Modifier.weight(1f).padding(end = 12.dp))
                Switch(showAll, onCheckedChange = null)
            }
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
        if (offers.isEmpty()) item { Panel("Nothing here yet", "Add clear reference selfies in You, then connect with friends. You can also turn on all event previews and manually mark a photo as yours.") }
        items(offers, key = { it.key }) { offer ->
            val owned = offer.ownerId == state.profile?.id
            val owner = if (owned) "your phone" else state.peers.find { it.peerId == offer.ownerId && it.eventId == event.id }?.nickname ?: "a friend"
            val status = when {
                offer.decision == MatchDecision.CONFIRMED -> "Confirmed by you"
                offer.decision == MatchDecision.REJECTED -> "Marked as not you"
                offer.match == MatchKind.SUGGESTED -> "Suggested photo of you"
                offer.match == MatchKind.UNCERTAIN -> "Not sure. Please check this photo."
                offer.match == MatchKind.MANUAL -> "Manually tagged as you"
                else -> "No face match. You can correct this manually."
            }
            Panel(offer.displayName, "$status From $owner.") {
                OfferThumbnail(offer)
                OutlinedButton(onClick = { model.decideMatch(offer, MatchDecision.CONFIRMED) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("That's me") }
                TextButton(onClick = { model.decideMatch(offer, MatchDecision.REJECTED) }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Not me") }
                if (!owned) {
                    Text("This is the last shared preview. The owner must be connected to supply an original.", style = MaterialTheme.typography.bodySmall)
                    val download = state.transfers.find { it.eventId == event.id && it.ownerId == offer.ownerId &&
                        it.photoId == offer.photoId && it.direction == TransferDirection.RECEIVE }
                    Button(onClick = {
                        if (download?.status == TransferStatus.COMPLETE && download.savedCopyAvailability != SavedCopyAvailability.MISSING) model.checkSavedPhoto(download)
                        else model.requestPhoto(offer)
                    }, enabled = !busy && (download == null || download.status in
                        setOf(TransferStatus.FAILED, TransferStatus.CANCELLED, TransferStatus.REJECTED, TransferStatus.WAITING, TransferStatus.COMPLETE)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(when (download?.status) {
                            TransferStatus.COMPLETE -> if (download.savedCopyAvailability == SavedCopyAvailability.MISSING) "Request original again" else "Check saved copy"
                            TransferStatus.AWAITING_APPROVAL -> "Awaiting owner approval"
                            TransferStatus.QUEUED, TransferStatus.TRANSFERRING -> "Download in progress"
                            TransferStatus.WAITING -> "Retry when friend is nearby"
                            else -> "Request original"
                        })
                    }
                    if (download?.status == TransferStatus.COMPLETE) download.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } else Text("You already have this original.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OfferThumbnail(offer: PhotoOffer) {
    val image = remember(offer.thumbnailBase64) {
        offer.thumbnailBase64?.let(::decodePreview)
    }
    if (image != null) Image(image, "Preview of ${offer.displayName}", Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Fit)
    else Text("No preview available. Ask the owner to refresh while both phones are connected.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TransfersPage(model: AppModel, state: AppState, event: Event, busy: Boolean) {
    val transfers = state.transfers.filter { it.eventId == event.id }.sortedBy { it.status != TransferStatus.AWAITING_APPROVAL }
    Page {
        item { Panel("Originals, with permission", "Approve the requests you are comfortable sharing. Original files can include location metadata. Saved copies go to Pictures/ThatsMyFace.") }
        if (transfers.isEmpty()) item { Panel("No requests yet", "Ask for an original in Of me, or wait for a connected friend to request one of your photos.") }
        items(transfers, key = { it.key }) { transfer -> TransferCard(model, state, transfer, busy) }
    }
}

@Composable
private fun TransferCard(model: AppModel, state: AppState, transfer: Transfer, busy: Boolean) {
    val context = LocalContext.current
    val sending = transfer.direction == TransferDirection.SEND
    val otherId = if (sending) transfer.receiverId else transfer.ownerId
    val friend = state.peers.find { it.peerId == otherId && it.eventId == transfer.eventId }?.nickname ?: "Friend"
    val status = when (transfer.status) {
        TransferStatus.WAITING -> "Waiting for your friend's phone"
        TransferStatus.AWAITING_APPROVAL -> if (sending) "Your approval is needed" else "Awaiting owner approval"
        TransferStatus.QUEUED -> "Queued"
        TransferStatus.TRANSFERRING -> if (sending) "Sending original" else "Downloading original"
        TransferStatus.COMPLETE -> if (sending) "Original delivered" else when (transfer.savedCopyAvailability) {
            SavedCopyAvailability.UNCHECKED -> "Original saved earlier; copy not checked"
            SavedCopyAvailability.AVAILABLE -> "Saved original checked"
            SavedCopyAvailability.MISSING -> "Saved copy is missing"
            SavedCopyAvailability.UNREADABLE -> "Saved copy could not be checked"
            SavedCopyAvailability.CHANGED -> "Saved copy changed"
        }
        TransferStatus.FAILED -> "Transfer failed"
        TransferStatus.REJECTED -> "Request declined"
        TransferStatus.CANCELLED -> "Cancelled"
    }
    Panel(transfer.displayName, "$status. ${if (sending) "To" else "From"} $friend.") {
        transfer.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (transfer.status in setOf(TransferStatus.QUEUED, TransferStatus.TRANSFERRING)) {
            LinearProgressIndicator(progress = { (transfer.bytesTransferred.toFloat() / transfer.size.coerceAtLeast(1)).coerceIn(0f, 1f) },
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
                        } catch (_: ActivityNotFoundException) { model.notify("No photo viewer is available. Open Pictures/ThatsMyFace in your gallery.") }
                        catch (_: SecurityException) { model.notify("The viewer could not access this copy. Check photo access and try again.") }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open saved photo") }
            }
        }
    }
}
