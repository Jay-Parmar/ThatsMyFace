package com.thatsmyface

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.thatsmyface.data.AppState
import com.thatsmyface.data.LocalStore
import com.thatsmyface.data.MatchKind
import com.thatsmyface.data.newId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AppModelInstrumentedTest {
    @Test fun selectedPhotoMovesThroughProfileTagsRealRecognitionFeedbackAndDeletion() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val model = fixture.model
            fixture.act { model.saveProfile("Private fixture nickname") }
            fixture.act { model.createEvent("Private fixture event") }
            val event = model.state.value.events.single()
            fixture.act { model.runAction { model.importIntoEvent(event.id, listOf(fixture.source), persist = false) } }
            val photo = model.state.value.photos.single()
            val profile = requireNotNull(model.state.value.profile)
            fixture.act { model.toggleTag(photo, profile.id) }
            assertEquals(listOf(profile.id), model.state.value.photos.single().manualPersonIds)
            assertEquals(MatchKind.MANUAL, model.state.value.offers.single().match)
            val preview = Base64.decode(requireNotNull(model.state.value.offers.single().thumbnailBase64), Base64.NO_WRAP)
            assertTrue(preview.size <= 18_432)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(preview, 0, preview.size, bounds)
            assertTrue(bounds.outWidth in 1..192 && bounds.outHeight in 1..192)
            fixture.act { model.toggleTag(model.state.value.photos.single(), profile.id) }
            fixture.act { model.enroll(listOf(fixture.source)) }
            assertEquals(128, model.state.value.profile!!.faceRefs.single().size)
            fixture.act { model.scanFaces() }
            assertEquals(MatchKind.SUGGESTED, model.state.value.offers.single().match)
            fixture.act { model.setFaceSharing(event.id, true) }
            assertTrue(model.state.value.events.single().shareFaceData)

            fixture.act { model.saveFeedback("Synthetic feedback note") }
            val exported = fixture.feedbackDestination()
            fixture.act { model.exportFeedback(exported) }
            val text = fixture.context.contentResolver.openInputStream(exported)!!.bufferedReader().use { it.readText() }
            assertTrue(text.contains("Synthetic feedback note"))
            assertTrue(text.contains(BuildConfig.VERSION_NAME))
            assertFalse(text.contains(profile.nickname))
            assertFalse(text.contains(event.secret))
            assertFalse(text.contains(photo.uri))
            assertFalse(text.contains("faceRefs"))

            fixture.act { model.deleteFaceData() }
            assertTrue(model.state.value.profile!!.faceRefs.isEmpty())
            assertFalse(model.state.value.events.single().shareFaceData)
            assertTrue(model.state.value.offers.isEmpty())
            fixture.act { model.deleteLocalData() }
            assertEquals(AppState(), model.state.value)
            assertArrayEquals(fixture.original, fixture.read(fixture.source))
            val reopened = LocalStore(fixture.application, fixture.keyAlias)
            reopened.load()
            assertEquals(AppState(), reopened.state.value)
        } finally { fixture.close() }
    }

    @Test fun capturedImportEventIsStableAndMissingSelectionsDoNotKeepSuggestions() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val model = fixture.model
            fixture.act { model.saveProfile("Fixture") }
            fixture.act { model.createEvent("First event") }
            val capturedEvent = model.selectedEventId.value!!
            fixture.act { model.createEvent("Second event") }
            val selectedEvent = model.selectedEventId.value
            fixture.act { model.runAction { model.importIntoEvent(capturedEvent, listOf(fixture.source), persist = false) } }
            assertEquals(capturedEvent, model.state.value.photos.single().eventId)
            assertEquals(selectedEvent, model.selectedEventId.value)
            fixture.act { model.importPhotos("deleted-event", listOf(fixture.source)) }
            assertEquals(1, model.state.value.photos.size)
            assertTrue(model.message.value!!.contains("event"))
            fixture.act { model.importPhotos(capturedEvent, listOf(Uri.parse("content://com.thatsmyface.test.storagefixture/revoked"))) }
            assertTrue(model.message.value!!.contains("Access was removed"))
            assertEquals(1, model.state.value.photos.size)

            withContext(Dispatchers.Main) { model.selectedEventId.value = capturedEvent }
            val photo = model.state.value.photos.single()
            fixture.act { model.toggleTag(photo, model.state.value.profile!!.id) }
            assertEquals(1, model.state.value.offers.size)
            fixture.context.contentResolver.delete(fixture.source, null, null)
            fixture.act { model.rescan() }
            assertTrue(model.state.value.offers.isEmpty())
            fixture.act { model.removePhoto(model.state.value.photos.single()) }
            assertTrue(model.state.value.photos.isEmpty())
        } finally { fixture.close() }
    }

    private class Fixture {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "app-model-tests/${newId()}").apply { mkdirs() }
        val keyAlias = "thatsmyface.test.model.${newId()}"
        val application = IsolatedApplication(context, root)
        val store = LocalStore(application, keyAlias)
        val original = InstrumentationRegistry.getInstrumentation().context.assets.open("astronaut.png").use { it.readBytes() }
        lateinit var model: AppModel
        lateinit var source: Uri
        private val createdUris = mutableListOf<Uri>()

        suspend fun initialize() {
            source = createMedia("image/png", "png", original)
            model = withContext(Dispatchers.Main) { AppModel(application, store) }
            withTimeout(10_000) { model.ready.first { it } }
        }

        suspend fun act(action: () -> Unit) {
            withContext(Dispatchers.Main) { action() }
            withTimeout(20_000) { model.busy.first { !it } }
        }

        fun read(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        fun feedbackDestination(): Uri = createMedia("text/plain", "txt", byteArrayOf())

        private fun createMedia(mimeType: String, extension: String, bytes: ByteArray): Uri {
            val collection = if (mimeType.startsWith("image/")) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "TMF_model_test_${newId()}.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (mimeType.startsWith("image/")) "Pictures/ThatsMyFaceTest/" else "Download/ThatsMyFaceTest/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            createdUris += uri
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }

        suspend fun close() {
            if (::model.isInitialized) withContext(Dispatchers.Main) { model.stopSharing(); model.viewModelScope.cancel() }
            store.clearAll()
            createdUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            root.deleteRecursively()
        }
    }

    private class IsolatedApplication(base: Context, root: File) : Application() {
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
