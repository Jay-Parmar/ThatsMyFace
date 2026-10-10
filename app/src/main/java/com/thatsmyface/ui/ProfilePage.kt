package com.thatsmyface.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.thatsmyface.BuildConfig
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event

@Composable
internal fun Onboarding(model: AppModel, busy: Boolean) {
    var nickname by rememberSaveable { mutableStateOf("") }
    Page { item { Panel("All your nights. All your faces.", "Find the photos your friends took of you, then ask for the originals. Start with a name they will recognize.") {
        OutlinedTextField(nickname, { nickname = it.take(32) }, label = { Text("Your nickname") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(onClick = { model.saveProfile(nickname) }, enabled = nickname.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Let's find your photos") }
        Text("No account. No cloud photo library. Face matching is optional and can be set up later.",
            style = MaterialTheme.typography.bodyMedium)
    } } }
}

@Composable
internal fun ProfilePage(model: AppModel, state: AppState, event: Event?, busy: Boolean) {
    var nickname by rememberSaveable(state.profile?.id) { mutableStateOf(state.profile?.nickname.orEmpty()) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val selfies = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.enroll(uris)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(model::exportFeedback)
    }
    Page {
        item { Panel("Hey, ${state.profile?.nickname.orEmpty()}", "Your profile is stored on this phone.") {
            OutlinedTextField(nickname, { nickname = it.take(32) }, label = { Text("Nickname") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedButton(onClick = { model.saveProfile(nickname) }, enabled = nickname.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save nickname") }
        } }
        item { Panel("Help your photos find you", "Optional: choose 1 to 5 clear selfies, with only you in each. Your phone creates face references. Selfie images are not sent to friends.") {
            Text("${state.profile?.faceRefs?.size ?: 0} face references saved", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { confirmation = "enroll" }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose reference selfies") }
            event?.let {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(it.shareFaceData, enabled = !busy,
                    role = Role.Switch, onValueChange = { enabled ->
                        if (enabled) confirmation = "share" else model.setFaceSharing(it.id, false)
                    }), verticalAlignment = Alignment.CenterVertically) {
                    Text("Share face references in ${it.title}", Modifier.weight(1f).padding(end = 12.dp))
                    Switch(it.shareFaceData, onCheckedChange = null)
                }
            }
            Text("A match is a suggestion, never proof of who someone is. Friends need verified connections and still approve original downloads.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmation = "faces" }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete local face data") }
        } }
        item { Panel("How did tonight go?", "Save a short note for the group. Feedback stays here until you export it. Leave out names, private photo details, and face data.") {
            OutlinedTextField(feedback, { feedback = it.take(1000) }, label = { Text("Your feedback") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Button(onClick = { model.saveFeedback(feedback); feedback = "" }, enabled = feedback.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save feedback") }
            OutlinedButton(onClick = { export.launch("ThatsMyFace-feedback.txt") },
                enabled = state.feedback.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Export ${state.feedback.size} saved notes")
            }
            Text("Exports include notes and app versions only. Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel("You're in control", "Removing access stops future sharing. Copies already downloaded cannot be recalled. Your source photos are never changed or deleted by cleanup.") {
            OutlinedButton(onClick = { confirmation = "all" }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete local app data") }
        } }
    }
    confirmation?.let { action ->
        val title: String
        val explanation: String
        val button: String
        when (action) {
            "enroll" -> {
                title = "Choose only your own face"
                explanation = "Choose up to five clear selfies with only you in them. New references replace your previous set. Face references stay local unless you turn sharing on for an event. You can delete them later."
                button = "Choose selfies"
            }
            "share" -> {
                title = "Share references with verified friends?"
                explanation = "Verified peers in this event will receive your face references so their phones can find you in selected photos. These references are sensitive. Switching this off stops future sharing and asks connected peers to delete them. Offline peers may retain previous references."
                button = "Enable for this event"
            }
            "faces" -> {
                title = "Delete local face data?"
                explanation = "This removes your saved references and face data received from friends, and switches off future reference sharing. Offline phones and copies already shared cannot be recalled."
                button = "Delete face data"
            }
            else -> {
                title = "Delete local app data?"
                explanation = "This removes your local profile, events, peer access, face data, transfer history, and saved feedback. Source photos and already downloaded pictures remain on their phones."
                button = "Delete app data"
            }
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text(explanation) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                when (action) {
                    "enroll" -> selfies.launch(arrayOf("image/*"))
                    "share" -> event?.let { model.setFaceSharing(it.id, true) }
                    "faces" -> model.deleteFaceData()
                    else -> model.deleteLocalData()
                }
            }) { Text(button) } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}
