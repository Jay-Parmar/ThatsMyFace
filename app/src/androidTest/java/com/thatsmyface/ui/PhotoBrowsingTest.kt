package com.thatsmyface.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.thatsmyface.AppModel
import com.thatsmyface.Invitations
import com.thatsmyface.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoBrowsingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeGalleryOnlyComposesNearbyTilesAndRetainsPositionAfterDetailAction() {
        val photos = (1..120).toList()
        var chosen: Int? = null
        compose.setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf<Int?>(null) }
                Box(Modifier.size(360.dp, 640.dp)) {
                    PhotoGrid {
                        items(photos, key = { it }) { number ->
                            PhotoTile("Photo $number", "Photo $number", "Selected event", { selected = number }) { Box(it) }
                        }
                    }
                }
                selected?.let { number ->
                    PhotoDetails("Photo details", { selected = null }) {
                        Button(onClick = { chosen = number }) { Text("Choose this photo") }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Photo 120").assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasContentDescription("Photo", substring = true)).fetchSemanticsNodes().size < 40)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(119)
        compose.onNodeWithContentDescription("Photo 120").performClick()
        compose.onNodeWithText("Choose this photo").performClick()
        compose.runOnIdle { assertEquals(120, chosen) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Photo 120").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo 1").assertDoesNotExist()
    }

    @Test fun approvalShortcutOpensIncomingRequestsAndApprovalPersistsWhileFriendIsAway() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "gallery-test/${newId()}").apply { mkdirs() }
        val application = TestApplication(context, root)
        val keyAlias = "thatsmyface.test.gallery.${newId()}"
        val store = LocalStore(application, keyAlias)
        val event = Invitations.create("Gallery fixture")
        val profile = Profile(nickname = "Owner fixture")
        val peer = Peer(event.id, newId(), "Friend fixture")
        val photo = Photo(event.id, uri = "content://com.thatsmyface.test.storagefixture/revoked",
            displayName = "Synthetic fixture", mimeType = "image/png", sha256 = "a".repeat(64), size = 12)
        val request = Transfer(event.id, profile.id, photo.id, peer.peerId, direction = TransferDirection.SEND,
            status = TransferStatus.AWAITING_APPROVAL, displayName = photo.displayName,
            mimeType = photo.mimeType, size = photo.size, sha256 = photo.sha256)
        store.load()
        store.update { AppState(profile = profile, events = listOf(event), peers = listOf(peer),
            photos = listOf(photo), transfers = listOf(request)) }
        val model = withContext(Dispatchers.Main) { AppModel(application, store) }
        try {
            withTimeout(10_000) { model.ready.first { it } }
            compose.setContent {
                val state by model.state.collectAsState()
                val busy by model.busy.collectAsState()
                MaterialTheme { PhotosPage(model, state, event, busy) }
            }
            compose.onNodeWithText("1 request needs your approval").performClick()
            compose.onNodeWithText("Approve original").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.transfers.single().approved }
            assertEquals(request.requestId, model.state.value.transfers.single().requestId)
            assertEquals(TransferStatus.WAITING, model.state.value.transfers.single().status)
            val reopened = LocalStore(application, keyAlias)
            reopened.load()
            assertTrue(reopened.state.value.transfers.single().approved)
            assertFalse(model.sharingActive.value)
        } finally {
            withContext(Dispatchers.Main) { model.stopSharing(); model.viewModelScope.cancel() }
            store.clearAll()
            root.deleteRecursively()
        }
    }

    private class TestApplication(base: Context, root: File) : Application() {
        init {
            attachBaseContext(object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }
                override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
                override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            })
        }
        override fun getApplicationContext(): Context = this
    }
}
