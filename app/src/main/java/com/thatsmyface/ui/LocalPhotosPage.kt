package com.thatsmyface.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thatsmyface.AppModel
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.data.PhotoAvailability

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LocalPhotosPage(model: AppModel, state: AppState, event: Event, busy: Boolean) {
    var policy by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var importEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var tagging by remember { mutableStateOf<String?>(null) }
    var selectedPhoto by rememberSaveable(event.id) { mutableStateOf<String?>(null) }
    var showOptions by rememberSaveable(event.id) { mutableStateOf(false) }
    var needsAttention by rememberSaveable(event.id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.importPhotos(importEventId, uris)
        importEventId = null
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.selectFolder(importEventId, it) }
        importEventId = null
    }
    val originalPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.notify("Gallery originals need access to photo location metadata. You can still try an exported file from a document provider.")
        if (importEventId == null || pendingPicker == null) {
            model.notify("The event selection was lost. Choose the event and open the picker again.")
        } else if (pendingPicker == "folder") folder.launch(null) else picker.launch(arrayOf("image/*"))
        pendingPicker = null
    }
    val photos = state.photos.filter { it.eventId == event.id }
    val unavailableCount = photos.count { it.availability != PhotoAvailability.AVAILABLE }
    val visiblePhotos = if (needsAttention) photos.filter { it.availability != PhotoAvailability.AVAILABLE } else photos
    PhotoGrid {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${photos.size} event photos", style = MaterialTheme.typography.titleLarge)
                Text("Chosen by you. Originals stay on this phone.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importEventId = event.id; policy = "photos" }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose photos") }
                    TextButton(onClick = { showOptions = !showOptions }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (showOptions) "Hide options" else "Library options")
                    }
                }
                if (showOptions) {
                    if (photos.isNotEmpty()) OutlinedButton(onClick = model::scanFaces, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Find faces in selected photos") }
                    OutlinedButton(onClick = { importEventId = event.id; policy = "folder" }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose an event folder") }
                    if (photos.isNotEmpty() || event.folderUri != null) OutlinedButton(onClick = model::rescan, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Check photos and rescan folder") }
                    Text(if (event.folderUri != null) "A folder is linked. Rescan reads that folder and its subfolders only."
                        else "Only selected photos or a chosen folder are read. Your full gallery is never scanned.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (photos.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !needsAttention, onClick = { needsAttention = false },
                    label = { Text("All ${photos.size}") }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(selected = needsAttention, onClick = { needsAttention = true },
                    label = { Text("Check $unavailableCount") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (photos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Panel("The night starts here", "Add the photos you want your verified event friends to find. You can add more after the next stop.")
        }
        else if (visiblePhotos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Panel("Nothing to check", "No selected photos currently need attention.")
        }
        items(visiblePhotos, key = { it.id }) { photo ->
            val label = if (photo.availability != PhotoAvailability.AVAILABLE) "Check access"
                else if (photo.manualPersonIds.isNotEmpty()) "Tagged" else "On your phone"
            PhotoTile(photo.displayName, label, photo.displayName, onClick = { selectedPhoto = photo.id }) {
                LocalPhotoPreview(model, photo, it)
            }
        }
    }
    selectedPhoto?.let { id -> photos.find { it.id == id }?.let { photo ->
        PhotoDetails(photo.displayName, onDismiss = { selectedPhoto = null }) {
            LocalPhotoPreview(model, photo, Modifier.fillMaxWidth().height(280.dp), detailed = true)
            Text(if (photo.availability == PhotoAvailability.AVAILABLE) "Original stays on this phone. Verified friends can see its preview; each original needs your approval."
                else photo.error ?: "This photo is unavailable. Select it again to restore access.")
            val names = photo.manualPersonIds.mapNotNull { personId ->
                if (personId == state.profile?.id) "You" else state.peers.find { it.peerId == personId && it.eventId == event.id }?.nickname
            }
            if (names.isNotEmpty()) Text("Tagged: ${names.joinToString()}", color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { tagging = photo.id }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tag or correct people") }
            TextButton(onClick = { selectedPhoto = null; model.removePhoto(photo) }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove from event") }
            Text("Removing from this event never deletes your original.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } }
    if (policy != null) AlertDialog(onDismissRequest = { policy = null }, title = { Text("Choose what friends can find") },
        text = { Text("Verified friends in this event can see previews of these photos. Every original download needs your approval. " +
            "Originals can contain location and camera metadata; Android may ask permission to preserve these bytes. " +
            if (policy == "folder") "Choosing a folder authorizes its photos and subfolders when you rescan. Use a dedicated event folder." else
                "Only the photos you select are included. You can remove them from the event later.") },
        confirmButton = { TextButton(onClick = {
            pendingPicker = policy
            policy = null
            originalPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }) { Text("Choose and continue") } },
        dismissButton = { TextButton(onClick = { policy = null }) { Text("Cancel") } })
    tagging?.let { id -> state.photos.find { it.id == id }?.let { photo ->
        val people = listOfNotNull(state.profile?.let { it.id to "You" }) + state.peers
            .filter { it.eventId == event.id && it.allowed }.map { it.peerId to it.nickname }
        AlertDialog(onDismissRequest = { tagging = null }, title = { Text("Who is in this photo?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Manual tags are a fallback and can correct a suggestion. They do not grant access.")
                people.forEach { (participantId, name) ->
                    val checked = participantId in photo.manualPersonIds
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = !busy,
                        role = Role.Checkbox, onValueChange = { model.toggleTag(photo, participantId) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null)
                        Text(name, Modifier.padding(start = 8.dp))
                    }
                }
            } }, confirmButton = { TextButton(onClick = { tagging = null }) { Text("Done") } })
    } }
}
