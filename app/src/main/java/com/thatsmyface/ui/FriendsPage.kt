package com.thatsmyface.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thatsmyface.AppModel
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.data.Peer
import com.thatsmyface.nearby.NearbyPermissions
import com.thatsmyface.nearby.PeerStatus

@Composable
internal fun FriendsPage(model: AppModel, state: AppState, event: Event?, busy: Boolean, openEvents: () -> Unit) {
    val nearby by model.nearbyPeers.collectAsStateWithLifecycle()
    val active by model.sharingActive.collectAsStateWithLifecycle()
    val sessionEvent by model.sharingEventId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var explanation by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<Peer?>(null) }
    var managingAccess by remember(event?.id) { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        denied = NearbyPermissions.missing(context).isNotEmpty()
        if (!denied) model.startSharing()
        else model.notify("Nearby access was denied. Allow nearby devices in app settings to connect.")
    }
    val sharing = active && event?.id == sessionEvent
    val verified = if (sharing) nearby.count { it.status == PeerStatus.VERIFIED } else 0
    Page {
        item { Panel(if (sharing) "Nearby sharing is on" else "Good photos travel together",
            if (sharing) "$verified verified friends. Keep both apps open while sharing."
            else "Join the same event, bring your phones close, and compare a short code.") {
            if (event == null) Button(onClick = openEvents, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose an event") }
            else if (sharing) {
                Text(event.title, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = model::stopSharing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop sharing") }
                TextButton(onClick = model::refreshFriends, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Resend my event photos") }
            } else Button(onClick = { explanation = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start nearby sharing") }
            TextButton(onClick = { showHelp = !showHelp }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showHelp) "Hide connection tips" else "Connection tips")
            }
            if (showHelp) {
                FeatureRow("1", "Use one invitation", "Join by the same QR. Matching event names are not enough.")
                FeatureRow("2", "Keep both phones ready", "Turn on Wi-Fi and Bluetooth. Start sharing on both phones.")
                FeatureRow("3", "Stay in the app", "Locking your phone, leaving the app, or opening a picker stops sharing. Restart and compare codes when you return.")
                Text("The screen stays awake while sharing. Older Android phones may also need location services on.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = openEvents, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open event invitations") }
            }
            if (denied) OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open app permissions") }
        } }
        if (sharing && nearby.isEmpty()) item { Panel("Looking for friends", "On the other phone, join this event by QR and tap Start nearby sharing. Keep both phones nearby with the apps open.") }
        if (nearby.isNotEmpty()) item { SectionHeading("Nearby phones", "Verify each friend before photos are exchanged.") }
        items(nearby, key = { it.endpointId }) { peer ->
            Panel(peer.nickname, when (peer.status) {
                PeerStatus.DISCOVERED -> "Nearby phone, not verified yet"
                PeerStatus.CONNECTING -> "Connecting"
                PeerStatus.VERIFYING -> "Compare the code on both phones"
                PeerStatus.WAITING_FOR_PEER -> "Waiting for your friend to verify"
                PeerStatus.CONNECTED -> "Checking event invitation"
                PeerStatus.VERIFIED -> "Verified and connected"
                PeerStatus.DISCONNECTED -> "Disconnected. Originals are unavailable until you reconnect."
            }) {
                when (peer.status) {
                    PeerStatus.DISCOVERED -> Button(onClick = { model.connect(peer.endpointId) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Connect") }
                    PeerStatus.VERIFYING -> {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("COMPARE ON BOTH PHONES", style = MaterialTheme.typography.labelSmall)
                                Text(peer.authenticationDigits.orEmpty(), style = MaterialTheme.typography.displaySmall,
                                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text("Look at your friend's screen. Approve only if this exact code appears on both phones.")
                        Button(onClick = { model.verify(peer.endpointId) }, enabled = !busy && !peer.authenticationDigits.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("The codes match") }
                        OutlinedButton(onClick = { model.rejectPeer(peer.endpointId) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reject connection") }
                    }
                    PeerStatus.VERIFIED -> Text("Browse their previews in Photos → Of me. Approve requests for your originals in Requested.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    else -> Unit
                }
            }
        }
        val savedPeers = state.peers.filter { it.eventId == event?.id }
        if (savedPeers.isNotEmpty()) item {
            OutlinedButton(onClick = { managingAccess = !managingAccess },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (managingAccess) "Hide event access" else "Manage event access (${savedPeers.size})")
            }
        }
        items(if (managingAccess) savedPeers else emptyList(), key = { it.peerId }) { peer ->
            Panel(peer.nickname, if (peer.allowed) "Allowed in this event. Connection status is shown above." else "Access removed") {
                if (peer.allowed) TextButton(onClick = { revoking = peer }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove future access") }
            }
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("Find nearby friends") },
        text = { Text("Android needs nearby-device permissions for Bluetooth and Wi-Fi connections. Older Android versions also require location permission. Photos and face references are exchanged only after both people verify the code and event invitation. Google Play services is required.") },
        confirmButton = { TextButton(onClick = {
            explanation = false
            val missing = NearbyPermissions.missing(context)
            if (missing.isEmpty()) model.startSharing() else permissions.launch(missing.toTypedArray())
        }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { explanation = false }) { Text("Cancel") } })
    revoking?.let { peer -> AlertDialog(onDismissRequest = { revoking = null }, title = { Text("Remove ${peer.nickname}'s access?") },
        text = { Text("This stops future sharing in this event. Pictures already downloaded cannot be recalled.") },
        confirmButton = { TextButton(onClick = { revoking = null; model.revokePeer(peer) }) { Text("Remove access") } },
        dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } }) }
}
