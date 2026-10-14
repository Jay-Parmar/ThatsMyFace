package com.thatsmyface.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.thatsmyface.AppModel
import com.thatsmyface.Invitations
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.data.LocalStore
import com.thatsmyface.data.MatchDecision
import com.thatsmyface.data.MatchKind
import com.thatsmyface.data.Peer
import com.thatsmyface.data.Photo
import com.thatsmyface.data.PhotoFiles
import com.thatsmyface.data.PhotoAvailability
import com.thatsmyface.data.PhotoOffer
import com.thatsmyface.data.Profile
import com.thatsmyface.data.TransferStatus
import com.thatsmyface.data.newId
import com.thatsmyface.recognition.FaceEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GalleryFilteringTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchFindsLastPhotoInLargeLibraryAndRespectsTagAndEventFilters() = runBlocking {
        val event = Invitations.create("Gallery fixture")
        val otherEvent = Invitations.create("Separate event")
        val profile = Profile(nickname = "Gallery owner")
        val photos = (1..120).map { index ->
            Photo(event.id, uri = "content://fixture/unused/$index", displayName = "shot-${index.toString().padStart(3, '0')}.jpg",
                mimeType = "image/jpeg", sha256 = "a".repeat(64), size = 12,
                availability = PhotoAvailability.PERMISSION_REVOKED,
                manualPersonIds = if (index == 120) listOf(profile.id) else emptyList())
        }
        withGallery(AppState(profile = profile, events = listOf(event, otherEvent),
            photos = photos + photos.last().copy(id = newId(), eventId = otherEvent.id)), event) {
            compose.onNodeWithContentDescription("shot-120.jpg").assertDoesNotExist()
            compose.onNodeWithText("Search").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("SHOT-120")
            compose.onNodeWithContentDescription("shot-120.jpg").performScrollTo().assertExists()
            compose.onNodeWithContentDescription("shot-001.jpg").assertDoesNotExist()
            compose.onNodeWithText("Clear").performScrollTo().performClick()
            compose.onNodeWithText("Tagged 1").performScrollTo().performClick()
            compose.onNodeWithContentDescription("shot-120.jpg").performScrollTo().assertExists()
            compose.onNodeWithContentDescription("shot-001.jpg").assertDoesNotExist()
        }
    }

    @Test fun reviewingUncertainMatchAndSearchingFriendLeadsToPersistedOriginalRequest() = runBlocking {
        val event = Invitations.create("Gallery fixture")
        val profile = Profile(nickname = "Gallery owner")
        val first = Peer(event.id, newId(), "Friend Alpha")
        val second = Peer(event.id, newId(), "Friend Beta")
        val offers = (1..120).map { index ->
            PhotoOffer(event.id, if (index == 120) second.peerId else first.peerId, newId(),
                "shared-$index.jpg", "image/jpeg", 12, sha256 = "a".repeat(64),
                match = if (index == 1) MatchKind.UNCERTAIN else MatchKind.AVAILABLE,
                decision = when (index) { 2 -> MatchDecision.CONFIRMED; 3 -> MatchDecision.REJECTED; else -> MatchDecision.NONE })
        }
        withGallery(AppState(profile = profile, events = listOf(event), peers = listOf(first, second), offers = offers), event) { model ->
            compose.onNodeWithText("Requested").performClick()
            compose.onNodeWithText("Received 0").performScrollTo().performClick()
            compose.onNodeWithText("Of me").performClick()
            compose.onNodeWithText("Uncertain 1").performScrollTo().performClick()
            compose.onNodeWithContentDescription("shared-1.jpg").performScrollTo().performClick()
            compose.onNodeWithText("That's me").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.offers.first().decision == MatchDecision.CONFIRMED }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Confirmed 2").performScrollTo().performClick()
            compose.onNodeWithContentDescription("shared-1.jpg").performScrollTo().assertExists()
            compose.onNodeWithContentDescription("shared-3.jpg").assertDoesNotExist()
            compose.onNodeWithText("All previews 120").performScrollTo().performClick()
            compose.onNodeWithText("Search").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("friend beta")
            compose.onNodeWithContentDescription("shared-120.jpg").performScrollTo().performClick()
            compose.onNodeWithText("Request original").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.transfers.size == 1 }
            compose.onNodeWithText("shared-120.jpg").assertIsDisplayed()
            compose.onNodeWithText("Waiting for your friend's phone", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Requested").assertIsSelected()
            compose.onNodeWithText("Received 0").performScrollTo().assertIsSelected()
            val request = model.state.value.transfers.single()
            assertEquals(second.peerId, request.ownerId)
            assertEquals(offers.last().photoId, request.photoId)
            assertEquals(TransferStatus.WAITING, request.status)
            assertFalse(model.sharingActive.value)
        }
    }

    @Test fun emptyDiscoveryRunsActualRecognitionAndPersistsTheUsersConfirmation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val event = Invitations.create("Local recognition fixture")
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "TMF_gallery_recognition_${newId()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ThatsMyFaceTest/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        try {
            resolver.openOutputStream(uri)!!.use { output ->
                instrumentation.context.assets.open("astronaut.png").use { it.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val photo = PhotoFiles(context).importPhoto(event.id, uri, persistPermission = false)
            val reference = FaceEngine(context).extract(uri).single()
            assertTrue(reference.suitableForEnrollment)
            val profile = Profile(nickname = "Licensed fixture", faceRefs = listOf(reference.embedding.toList()))
            withGallery(AppState(profile = profile, events = listOf(event), photos = listOf(photo)), event) { model ->
                compose.onNodeWithText("Of me").performClick()
                compose.onNodeWithText("Find me in my photos").performScrollTo().performClick()
                compose.waitUntil(60_000) { model.state.value.offers.singleOrNull()?.match == MatchKind.SUGGESTED }
                assertEquals(profile.id, model.state.value.offers.single().ownerId)
                compose.onNodeWithContentDescription(photo.displayName).performScrollTo().performClick()
                compose.onNodeWithText("That's me").performScrollTo().performClick()
                compose.waitUntil(10_000) { model.state.value.offers.single().decision == MatchDecision.CONFIRMED }
                assertFalse(model.sharingActive.value)
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private suspend fun withGallery(state: AppState, event: Event, test: suspend (AppModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "gallery-filter/${newId()}").apply { mkdirs() }
        val application = TestApplication(context, root)
        val keyAlias = "thatsmyface.test.gallery.filter.${newId()}"
        val store = LocalStore(application, keyAlias)
        store.load()
        store.update { state }
        val model = withContext(Dispatchers.Main) { AppModel(application, store) }
        try {
            withTimeout(10_000) { model.ready.first { it } }
            compose.setContent {
                val current by model.state.collectAsState()
                val busy by model.busy.collectAsState()
                MaterialTheme { PhotosPage(model, current, event, busy, openFriends = {}, openProfile = {}) }
            }
            test(model)
            val reopened = LocalStore(application, keyAlias)
            reopened.load()
            assertEquals(model.state.value.offers.map { it.decision }, reopened.state.value.offers.map { it.decision })
            assertEquals(model.state.value.transfers.map { it.status }, reopened.state.value.transfers.map { it.status })
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
