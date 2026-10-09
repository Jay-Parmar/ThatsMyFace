package com.thatsmyface.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun androidKeyStorePersistsEncryptedDataAndDeletionSurvivesRestart() = runBlocking {
        val fixture = PrivateFixture(context)
        val store = fixture.newStore()
        try {
            val profile = Profile(nickname = "Synthetic test", faceRefs = listOf(List(128) { 0.125f }))
            store.update { it.copy(profile = profile, events = listOf(Event(title = "Test", secret = "1".repeat(64), shareFaceData = true))) }
            val encrypted = fixture.stateFile.readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains(profile.nickname))
            val reopened = fixture.newStore()
            reopened.load()
            assertEquals(profile, reopened.state.value.profile)
            reopened.clearFaceData()
            val afterFaceDeletion = fixture.newStore()
            afterFaceDeletion.load()
            assertTrue(afterFaceDeletion.state.value.profile!!.faceRefs.isEmpty())
            assertFalse(afterFaceDeletion.state.value.events.single().shareFaceData)
            afterFaceDeletion.clearAll()
            val empty = fixture.newStore()
            empty.load()
            assertEquals(AppState(), empty.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test fun corruptedLocalDataDoesNotBecomeAnEmptyWritableStore() = runBlocking {
        val fixture = PrivateFixture(context)
        val store = fixture.newStore()
        try {
            store.update { it.copy(profile = Profile(nickname = "Synthetic")) }
            val file = fixture.stateFile
            val corrupted = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            file.writeBytes(corrupted)
            val reopened = fixture.newStore()
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
            fixture.close()
        }
    }

    @Test fun interruptedAtomicWriteRestoresCommittedDataAndAllowsTheNextUpdate() = runBlocking {
        val fixture = PrivateFixture(context)
        try {
            val store = fixture.newStore()
            val committed = AppState(profile = Profile(nickname = "Committed fixture"))
            store.update { committed }
            val encrypted = fixture.stateFile.readBytes()
            // Closing without finishWrite simulates a process dying before its write is committed.
            AtomicFile(fixture.stateFile).startWrite().use { it.write(byteArrayOf(1, 2, 3)) }
            val reopened = fixture.newStore()
            reopened.load()
            assertEquals(committed, reopened.state.value)
            assertArrayEquals(encrypted, fixture.stateFile.readBytes())
            val feedback = Feedback(note = "After interrupted write", version = "test")
            reopened.update { it.copy(feedback = listOf(feedback)) }
            val afterNextWrite = fixture.newStore()
            afterNextWrite.load()
            assertEquals(committed.copy(feedback = listOf(feedback)), afterNextWrite.state.value)
        } finally { fixture.close() }
    }

    @Test fun concurrentLocalUpdatesRemainPresentAfterRestart() = runBlocking {
        val fixture = PrivateFixture(context)
        try {
            val store = fixture.newStore()
            val notes = List(24) { Feedback(id = "note-$it", note = "Synthetic note $it", version = "test") }
            notes.map { note ->
                async(Dispatchers.Default) { store.update { it.copy(feedback = it.feedback + note) } }
            }.awaitAll()
            assertEquals(notes.toSet(), store.state.value.feedback.toSet())
            assertEquals(notes.size, store.state.value.feedback.size)
            val reopened = fixture.newStore()
            reopened.load()
            assertEquals(store.state.value, reopened.state.value)
        } finally { fixture.close() }
    }

    @Test fun explicitResetErasesTheKeySoAnOldEncryptedCopyCannotBeRestored() = runBlocking {
        val fixture = PrivateFixture(context)
        try {
            val store = fixture.newStore()
            store.update { it.copy(profile = Profile(nickname = "Reset fixture", faceRefs = listOf(List(128) { 0.25f }))) }
            val encrypted = fixture.stateFile.readBytes()
            store.clearAll()
            assertEquals(AppState(), store.state.value)
            assertFalse(fixture.stateFile.exists())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(fixture.keyAlias))
            fixture.stateFile.writeBytes(encrypted)
            val reopened = fixture.newStore()
            try {
                reopened.load()
                fail("Erased face data must not be recovered from an old encrypted file")
            } catch (_: LocalDataException) {
                assertEquals(AppState(), reopened.state.value)
                assertArrayEquals(encrypted, fixture.stateFile.readBytes())
            }
        } finally { fixture.close() }
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
            val originalFile = temporary
            val concurrentCopies = List(4) {
                async { files.saveReceived(originalFile, transfer.copy(requestId = newId())) }
            }.awaitAll()
            assertEquals(setOf(received), concurrentCopies.toSet())
            assertEquals(listOf(received), savedCopies(transfer))
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
        val fixture = PrivateFixture(context)
        val photo = Photo(eventId = "event", uri = "content://com.thatsmyface.test.storagefixture/revoked",
            displayName = "synthetic.jpg", mimeType = "image/jpeg", size = 123, sha256 = "a".repeat(64))
        try {
            val files = PhotoFiles(fixture.context)
            val checked = files.checkAvailability(photo)
            assertEquals(PhotoAvailability.PERMISSION_REVOKED, checked.availability)
            assertNotNull(checked.error)
            val state = AppState(events = listOf(Event(id = "event", title = "Test", secret = "b".repeat(64))),
                photos = listOf(checked), peers = listOf(Peer("event", "friend", "Friend")))
            assertFalse(state.canAccessPhoto("event", "friend", photo.id))
            try {
                files.outgoingSnapshot(photo)
                fail("A revoked source must not produce an outgoing snapshot")
            } catch (error: PhotoAccessException) {
                assertEquals(PhotoAvailability.PERMISSION_REVOKED, error.availability)
                assertTrue(File(fixture.context.cacheDir, "outgoing").listFiles()!!.isEmpty())
            }
        } finally { fixture.close() }
    }

    @Test fun sourceChangesAfterImportBlockSharingAndRemoveTheFailedSnapshot() = runBlocking {
        val fixture = PrivateFixture(context)
        val source = createSyntheticPhoto()
        try {
            val files = PhotoFiles(fixture.context)
            val photo = files.importPhoto("changed-source-event", source, persistPermission = false)
            val changed = context.contentResolver.openInputStream(source)!!.use { it.readBytes() } + byteArrayOf(7, 8, 9)
            context.contentResolver.openOutputStream(source, "wt")!!.use { it.write(changed) }
            val checked = files.checkAvailability(photo)
            assertEquals(PhotoAvailability.CHANGED, checked.availability)
            assertNotNull(checked.error)
            try {
                files.outgoingSnapshot(photo)
                fail("A source edited after selection must be selected again before sharing")
            } catch (error: PhotoAccessException) {
                assertEquals(PhotoAvailability.CHANGED, error.availability)
                assertTrue(File(fixture.context.cacheDir, "outgoing").listFiles()!!.isEmpty())
                assertArrayEquals(changed, context.contentResolver.openInputStream(source)!!.use { it.readBytes() })
            }
        } finally {
            context.contentResolver.delete(source, null, null)
            fixture.close()
        }
    }

    @Test fun savedCopyChecksDistinguishMissingChangedAndDeniedWithoutDeletingExistingBytes() = runBlocking {
        val files = PhotoFiles(context)
        val source = createSyntheticPhoto()
        var received: Uri? = null
        var temporary: File? = null
        try {
            val photo = files.importPhoto("saved-copy-event", source, persistPermission = false)
            temporary = files.outgoingSnapshot(photo)
            val transfer = Transfer(photo.eventId, "sender", photo.id, "receiver", direction = TransferDirection.RECEIVE,
                status = TransferStatus.COMPLETE, displayName = photo.displayName, mimeType = photo.mimeType,
                size = photo.size, sha256 = photo.sha256)
            received = files.saveReceived(temporary, transfer)
            val completed = transfer.copy(savedUri = received.toString())
            assertEquals(SavedCopyAvailability.AVAILABLE, files.checkSavedCopy(completed).availability)
            val changed = byteArrayOf(1, 2, 3, 4)
            context.contentResolver.openOutputStream(received, "wt")!!.use { it.write(changed) }
            assertEquals(SavedCopyAvailability.CHANGED, files.checkSavedCopy(completed).availability)
            try {
                files.saveReceived(temporary, transfer)
                fail("A changed saved copy must not be overwritten")
            } catch (_: IllegalStateException) {
                assertArrayEquals(changed, context.contentResolver.openInputStream(received)!!.use { it.readBytes() })
            }
            context.contentResolver.delete(received, null, null)
            received = null
            assertEquals(SavedCopyAvailability.MISSING, files.checkSavedCopy(completed).availability)
            val denied = completed.copy(savedUri = "content://com.thatsmyface.test.storagefixture/revoked")
            assertEquals(SavedCopyAvailability.UNREADABLE, files.checkSavedCopy(denied).availability)
            assertEquals(PhotoAvailability.AVAILABLE, files.checkAvailability(photo).availability)
        } finally {
            context.contentResolver.delete(source, null, null)
            received?.let { context.contentResolver.delete(it, null, null) }
            temporary?.delete()
        }
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

    @Test fun retryRecoversBothCompleteAndTruncatedPendingCopiesWithoutDuplicates() = runBlocking {
        val source = createSyntheticPhoto()
        val files = PhotoFiles(context)
        var temporary: File? = null
        val transfers = mutableListOf<Transfer>()
        try {
            val photo = files.importPhoto("pending-save-event", source, persistPermission = false)
            temporary = files.outgoingSnapshot(photo)
            val original = temporary.readBytes()
            for (truncated in listOf(false, true)) {
                val transfer = Transfer(photo.eventId, "sender", newId(), "receiver", direction = TransferDirection.RECEIVE,
                    displayName = photo.displayName, mimeType = photo.mimeType, size = photo.size, sha256 = photo.sha256)
                transfers += transfer
                val pending = files.saveReceived(temporary, transfer)
                context.contentResolver.update(pending, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
                if (truncated) context.contentResolver.openOutputStream(pending, "wt")!!.use { it.write(original.copyOf(original.size / 2)) }
                val restartedFiles = PhotoFiles(context)
                val recovered = restartedFiles.saveReceived(temporary, transfer.copy(requestId = newId()))
                if (!truncated) assertEquals(pending, recovered)
                assertEquals(listOf(recovered), savedCopies(transfer))
                assertArrayEquals(original, context.contentResolver.openInputStream(recovered)!!.use { it.readBytes() })
                context.contentResolver.query(recovered, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
            assertArrayEquals(original, context.contentResolver.openInputStream(source)!!.use { it.readBytes() })
        } finally {
            transfers.flatMap(::savedCopies).forEach { context.contentResolver.delete(it, null, null) }
            context.contentResolver.delete(source, null, null)
            temporary?.delete()
        }
    }

    @Test fun invalidIncomingBytesCreateNoSavedCopyAndAValidRetryStillWorks() = runBlocking {
        val source = createSyntheticPhoto()
        val files = PhotoFiles(context)
        var temporary: File? = null
        var received: Uri? = null
        try {
            val photo = files.importPhoto("invalid-save-event", source, persistPermission = false)
            temporary = files.outgoingSnapshot(photo)
            val original = temporary.readBytes()
            val transfer = Transfer(photo.eventId, "sender", photo.id, "receiver", direction = TransferDirection.RECEIVE,
                displayName = photo.displayName, mimeType = photo.mimeType, size = photo.size, sha256 = photo.sha256)
            temporary.writeBytes(original.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
            try {
                files.saveReceived(temporary, transfer)
                fail("Bytes with a different checksum must not be saved")
            } catch (_: IllegalArgumentException) {
                assertTrue(savedCopies(transfer).isEmpty())
            }
            val notAnImage = byteArrayOf(1, 2, 3, 4)
            temporary.writeBytes(notAnImage)
            try {
                files.saveReceived(temporary, transfer.copy(size = notAnImage.size.toLong(), sha256 = sha256(notAnImage)))
                fail("A correct checksum does not make arbitrary bytes a photo")
            } catch (_: IllegalArgumentException) {
                assertTrue(savedCopies(transfer).isEmpty())
            }
            temporary.writeBytes(original)
            received = files.saveReceived(temporary, transfer)
            assertEquals(listOf(received), savedCopies(transfer))
            assertArrayEquals(original, context.contentResolver.openInputStream(received)!!.use { it.readBytes() })
            assertArrayEquals(original, context.contentResolver.openInputStream(source)!!.use { it.readBytes() })
        } finally {
            received?.let { context.contentResolver.delete(it, null, null) }
            context.contentResolver.delete(source, null, null)
            temporary?.delete()
        }
    }

    @Test fun folderImportAcceptsJpgAliasesAndKeepsOnlyAuthorizedImageBytes() = runBlocking {
        val fixture = PrivateFixture(context)
        try {
            val image = File(fixture.context.cacheDir, "folder-original.jpg")
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff604080.toInt()) }
            try {
                image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            val original = image.readBytes()
            val provider = FolderProvider(image)
            provider.attachInfo(fixture.context, ProviderInfo().apply { authority = "com.thatsmyface.test.folder" })
            val wrappedResolver = ContentResolver.wrap(provider)
            val folderContext = object : ContextWrapper(fixture.context) {
                override fun getContentResolver(): ContentResolver = wrappedResolver
            }
            val files = PhotoFiles(folderContext)
            val tree = DocumentsContract.buildTreeDocumentUri("com.thatsmyface.test.folder", "event")
            val selected = files.listFolder(tree, persistPermission = false)
            assertEquals(setOf("event/alias", "event/nested/photo"), selected.map(DocumentsContract::getDocumentId).toSet())
            assertEquals(setOf("event", "event/nested"), provider.queriedFolders)
            for (uri in selected) {
                val photo = files.importPhoto("folder-event", uri, persistPermission = false)
                assertEquals("image/jpeg", photo.mimeType)
                assertEquals(sha256(original), photo.sha256)
                assertEquals(original.size.toLong(), photo.size)
                val snapshot = files.outgoingSnapshot(photo)
                try { assertArrayEquals(original, snapshot.readBytes()) }
                finally { snapshot.delete() }
            }
            assertArrayEquals(original, image.readBytes())
        } finally { fixture.close() }
    }

    @Suppress("DEPRECATION")
    private fun savedCopies(transfer: Transfer): List<Uri> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ?",
            arrayOf("TMF_${transfer.key}_%", context.packageName), null)!!.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Uri.withAppendedPath(collection, cursor.getLong(0).toString()))
            }
        }
    }

    private class FolderProvider(private val image: File) : ContentProvider() {
        val queriedFolders = mutableSetOf<String>()
        private val rows = mapOf(
            "event" to listOf(
                arrayOf("event/alias", "IMAGE/JPG", "alias.jpg"),
                arrayOf("event/nested", DocumentsContract.Document.MIME_TYPE_DIR, "Nested"),
                arrayOf("event/note", "text/plain", "note.txt"),
            ),
            "event/nested" to listOf(
                arrayOf("event/nested/photo", "image/jpeg", "photo.jpg"),
                arrayOf("event/nested/video", "video/mp4", "video.mp4"),
            ),
        )

        override fun onCreate() = true
        override fun getType(uri: Uri): String = document(DocumentsContract.getDocumentId(uri))[1]
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r" && DocumentsContract.getDocumentId(uri) in setOf("event/alias", "event/nested/photo"))
            return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = DocumentsContract.getDocumentId(uri)
            val documents = if (uri.lastPathSegment == "children") {
                queriedFolders += id
                requireNotNull(rows[id]) { "Traversal left the selected tree" }
            } else listOf(document(id))
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                for (document in documents) addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> document[0]
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> document[1]
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> document[2]
                        else -> error("Unexpected fixture column")
                    }
                })
            }
        }
        private fun document(id: String) = rows.values.flatten().first { it[0] == id }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    }

    private class PrivateFixture(base: Context) {
        private val root = File(base.cacheDir, "storage-tests/${newId()}").apply { mkdirs() }
        val keyAlias = "thatsmyface.test.storage.${newId()}"
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val stateFile get() = File(context.noBackupFilesDir, "local-state.v1.enc")
        fun newStore() = LocalStore(context, keyAlias)
        suspend fun close() {
            newStore().clearAll()
            root.deleteRecursively()
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
