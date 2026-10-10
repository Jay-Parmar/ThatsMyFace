package com.thatsmyface.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thatsmyface.AppModel
import com.thatsmyface.R

@Composable
fun ThatsMyFaceApp(model: AppModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()
    val loadFailed by model.loadFailed.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val eventId by model.selectedEventId.collectAsStateWithLifecycle()
    val selectedEvent = state.events.find { it.id == eventId }
    val approvals = state.transfers.count { it.eventId == selectedEvent?.id && it.needsOwnerDecision() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var resetConfirmation by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            model.dismissMessage()
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFD7EC92), onPrimary = Color(0xFF203000), secondary = Color(0xFFAEDFD5),
        primaryContainer = Color(0xFF34431B), onPrimaryContainer = Color(0xFFE1F3AA),
        secondaryContainer = Color(0xFF2B4039), onSecondaryContainer = Color(0xFFD2F1E7),
        background = Color(0xFF101410), surface = Color(0xFF171D17), surfaceVariant = Color(0xFF293127),
        onSurface = Color(0xFFF0F3E9), onSurfaceVariant = Color(0xFFC3CCBB),
    )) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp, 12.dp)) {
                    Text("ThatsMyFace", style = MaterialTheme.typography.titleLarge)
                    Text(selectedEvent?.title ?: "If you're in it, find it.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
                if (state.profile != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val labels = listOf("Events", "Photos", "Friends", "You")
                    val icons = listOf(R.drawable.ic_events, R.drawable.ic_photos, R.drawable.ic_friends, R.drawable.ic_profile)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { BadgedBox(badge = {
                                if (index == 1 && approvals > 0) Badge { Text(approvals.toString()) }
                            }) { Icon(painterResource(icons[index]), contentDescription = null) } },
                            label = { Text(label) })
                    }
                }
            }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    when {
                        !ready && loadFailed -> Page { item {
                            Panel("Your saved data could not be opened", "The encryption key or saved state may be unavailable. Restart the app to try again. You can reset local app data if it cannot be recovered.") {
                                Button(onClick = { resetConfirmation = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Reset local app data")
                                }
                            }
                        } }
                        !ready -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Opening your photos and events", Modifier.padding(16.dp))
                        }
                        state.profile == null -> Onboarding(model, busy)
                        tab == 0 -> EventsPage(model, state, selectedEvent, busy) { tab = 1 }
                        tab == 1 -> if (selectedEvent == null) {
                            Page { item { Panel("Pick your gathering", "Create or join an event before adding photos.") {
                                Button(onClick = { tab = 0 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose an event") }
                            } } }
                        } else PhotosPage(model, state, selectedEvent, busy)
                        tab == 2 -> FriendsPage(model, state, selectedEvent, busy)
                        else -> ProfilePage(model, state, selectedEvent, busy)
                    }
                }
            }
            if (resetConfirmation) AlertDialog(onDismissRequest = { resetConfirmation = false },
                title = { Text("Reset local app data?") },
                text = { Text("Saved profiles, events, face references, transfer history, and feedback will be removed. Source photos and already downloaded photos remain on the phone.") },
                confirmButton = { TextButton(onClick = { resetConfirmation = false; model.deleteLocalData() }) { Text("Reset data") } },
                dismissButton = { TextButton(onClick = { resetConfirmation = false }) { Text("Cancel") } })
        }
    }
}

@Composable
internal fun Page(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
internal fun Panel(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}
