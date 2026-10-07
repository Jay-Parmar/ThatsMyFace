package com.thatsmyface.data

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun androidKeyStorePersistsEncryptedDataAndDeletionSurvivesRestart() = runBlocking {
        val store = LocalStore(context)
        store.clearAll()
        try {
            val profile = Profile(nickname = "Synthetic test", faceRefs = listOf(List(128) { 0.125f }))
            store.update { it.copy(profile = profile, events = listOf(Event(title = "Test", secret = "1".repeat(64), shareFaceData = true))) }
            val encrypted = File(context.noBackupFilesDir, "local-state.v1.enc").readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains(profile.nickname))
            val reopened = LocalStore(context)
            reopened.load()
            assertEquals(profile, reopened.state.value.profile)
            reopened.clearFaceData()
            val afterFaceDeletion = LocalStore(context)
            afterFaceDeletion.load()
            assertTrue(afterFaceDeletion.state.value.profile!!.faceRefs.isEmpty())
            assertFalse(afterFaceDeletion.state.value.events.single().shareFaceData)
            afterFaceDeletion.clearAll()
            val empty = LocalStore(context)
            empty.load()
            assertEquals(AppState(), empty.state.value)
        } finally {
            store.clearAll()
        }
    }

    @Test fun corruptedLocalDataDoesNotBecomeAnEmptyWritableStore() = runBlocking {
        val store = LocalStore(context)
        store.clearAll()
        try {
            store.update { it.copy(profile = Profile(nickname = "Synthetic")) }
            val file = File(context.noBackupFilesDir, "local-state.v1.enc")
            val corrupted = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            file.writeBytes(corrupted)
            val reopened = LocalStore(context)
            try {
                reopened.load()
                fail("Corrupted state must not load")
            } catch (_: LocalDataException) {
                // The only recovery is an explicit local reset.
            }
            try {
                reopened.update { it.copy(profile = Profile(nickname = "Overwrite")) }
                fail("Corrupted state must not be overwritten")
            } catch (_: LocalDataException) {
                assertArrayEquals(corrupted, file.readBytes())
            }
        } finally {
            store.clearAll()
        }
    }

    @Test fun originalBytesSurviveTransferAndRepeatedSaveDoesNotDuplicate() = runBlocking {
        val files = PhotoFiles(context)
        val source = createSyntheticPhoto()
        var received: Uri? = null
        var temporary: File? = null
        var sourceDeleted = false
        try {
            val photo = files.importPhoto("test-event", source, persistPermission = false)
            val original = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
            temporary = files.outgoingSnapshot(photo)
            assertArrayEquals(original, temporary.readBytes())
            val transfer = Transfer("test-event", "sender", photo.id, "receiver", direction = TransferDirection.RECEIVE,
                displayName = photo.displayName, mimeType = photo.mimeType, size = photo.size, sha256 = photo.sha256)
            received = files.saveReceived(temporary, transfer)
            assertEquals(received, files.saveReceived(temporary, transfer.copy(requestId = newId())))
            val saved = context.contentResolver.openInputStream(received)!!.use { it.readBytes() }
            assertArrayEquals(original, saved)
            assertArrayEquals(original, context.contentResolver.openInputStream(source)!!.use { it.readBytes() })
            context.contentResolver.delete(source, null, null)
            sourceDeleted = true
            val unavailable = files.checkAvailability(photo)
            assertTrue(unavailable.availability in setOf(PhotoAvailability.MISSING, PhotoAvailability.PERMISSION_REVOKED))
            assertNotNull(unavailable.error)
        } finally {
            if (!sourceDeleted) context.contentResolver.delete(source, null, null)
            received?.let { context.contentResolver.delete(it, null, null) }
            temporary?.delete()
        }
    }

    @Test fun revokedProviderAccessIsActionableAndBlocksSharing() = runBlocking {
        val photo = Photo(eventId = "event", uri = "content://com.thatsmyface.test.storagefixture/revoked",
            displayName = "synthetic.jpg", mimeType = "image/jpeg", size = 123, sha256 = "a".repeat(64))
        val checked = PhotoFiles(context).checkAvailability(photo)
        assertEquals(PhotoAvailability.PERMISSION_REVOKED, checked.availability)
        assertNotNull(checked.error)
        val state = AppState(events = listOf(Event(id = "event", title = "Test", secret = "b".repeat(64))),
            photos = listOf(checked), peers = listOf(Peer("event", "friend", "Friend")))
        assertFalse(state.canAccessPhoto("event", "friend", photo.id))
    }

    @Test fun cancellationBeforePublicationRemovesOnlyThePendingCopy() = runBlocking {
        val files = PhotoFiles(context)
        val source = createSyntheticPhoto()
        var temporary: File? = null
        try {
            val photo = files.importPhoto("cancel-event", source, persistPermission = false)
            temporary = files.outgoingSnapshot(photo)
            val transfer = Transfer("cancel-event", "sender", photo.id, "receiver", direction = TransferDirection.RECEIVE,
                displayName = photo.displayName, mimeType = photo.mimeType, size = photo.size, sha256 = photo.sha256)
            var checks = 0
            try {
                files.saveReceived(temporary, transfer) { ++checks == 1 }
                fail("A cancelled copy must not be published")
            } catch (_: IllegalStateException) {
                assertEquals(2, checks)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            @Suppress("DEPRECATION")
            val pendingCollection = MediaStore.setIncludePending(collection)
            context.contentResolver.query(pendingCollection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("TMF_${transfer.key}_%"), null)!!.use {
                assertEquals(0, it.count)
            }
            assertEquals(PhotoAvailability.AVAILABLE, files.checkAvailability(photo).availability)
        } finally {
            context.contentResolver.delete(source, null, null)
            temporary?.delete()
        }
    }

    private fun createSyntheticPhoto(): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "thatsmyface-test-${newId()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ThatsMyFaceTests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff8040cc.toInt()) }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
