package com.thatsmyface

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.nearby.connection.Payload
import com.thatsmyface.data.Event
import com.thatsmyface.data.LocalStore
import com.thatsmyface.data.PhotoFiles
import com.thatsmyface.data.Profile
import com.thatsmyface.data.Transfer
import com.thatsmyface.data.TransferStatus
import com.thatsmyface.data.SavedCopyAvailability
import com.thatsmyface.data.newId
import com.thatsmyface.nearby.NearbyLink
import com.thatsmyface.nearby.NearbyPeer
import com.thatsmyface.nearby.PayloadStatus
import com.thatsmyface.nearby.PeerStatus
import com.thatsmyface.nearby.PreparedFile
import com.thatsmyface.nearby.TransportEvent
import com.thatsmyface.nearby.WireCodec
import com.thatsmyface.nearby.WireMessage
import com.thatsmyface.recognition.FaceEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

class SharingSessionInstrumentedTest {
    @Test fun approvedOriginalTraversesRealSessionStoresAndMediaStoreWithoutDuplicates() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            assertEquals(0, fixture.ownerLink.filesSent)
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.COMPLETE && fixture.ownerTransfer().status == TransferStatus.COMPLETE }
            val saved = requireNotNull(fixture.receiverTransfer()?.savedUri)
            assertArrayEquals(fixture.original, fixture.read(Uri.parse(saved)))
            assertArrayEquals(fixture.original, fixture.read(fixture.source))
            withContext(Dispatchers.Main) { fixture.receiver.request(fixture.receiverStore.state.value.offers.single()) }
            assertEquals(1, fixture.ownerLink.filesSent)
            val restored = LocalStore(fixture.receiverContext, fixture.receiverKey)
            restored.load()
            assertEquals(saved, restored.state.value.transfers.single().savedUri)
            assertEquals(TransferStatus.COMPLETE, restored.state.value.transfers.single().status)
        } finally { fixture.close() }
    }

    @Test fun deletedSavedOriginalNeedsFreshOwnerApprovalAndSavesOnlyOneReplacement() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.COMPLETE && fixture.ownerTransfer().status == TransferStatus.COMPLETE }
            val completed = requireNotNull(fixture.receiverTransfer())
            fixture.context.contentResolver.delete(Uri.parse(completed.savedUri), null, null)
            withContext(Dispatchers.Main) { fixture.receiver.checkSavedCopy(completed) }
            assertEquals(TransferStatus.COMPLETE, fixture.receiverTransfer()?.status)
            assertEquals(SavedCopyAvailability.MISSING, fixture.receiverTransfer()?.savedCopyAvailability)
            assertEquals(TransferStatus.COMPLETE, fixture.ownerTransfer().status)

            withContext(Dispatchers.Main) { fixture.receiver.retry(requireNotNull(fixture.receiverTransfer())) }
            fixture.await { fixture.ownerTransfer().status == TransferStatus.AWAITING_APPROVAL }
            assertNotEquals(completed.requestId, fixture.ownerTransfer().requestId)
            assertFalse(fixture.ownerTransfer().approved)
            assertEquals(1, fixture.ownerLink.filesSent)
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.COMPLETE && fixture.ownerTransfer().status == TransferStatus.COMPLETE }
            val replacement = requireNotNull(fixture.receiverTransfer())
            assertEquals(SavedCopyAvailability.AVAILABLE, replacement.savedCopyAvailability)
            assertArrayEquals(fixture.original, fixture.read(Uri.parse(replacement.savedUri)))
            assertArrayEquals(fixture.original, fixture.read(fixture.source))
            withContext(Dispatchers.Main) { fixture.receiver.retry(replacement) }
            assertEquals(2, fixture.ownerLink.filesSent)
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            fixture.context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("TMF_${replacement.key}_%"), null)!!.use {
                assertEquals(1, it.count)
            }
        } finally { fixture.close() }
    }

    @Test fun restoredOrUnreadableSavedCopyDoesNotCreateAnotherRequest() = runBlocking {
        val fixture = Fixture()
        var savedUri: String? = null
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.COMPLETE && fixture.ownerTransfer().status == TransferStatus.COMPLETE }
            val completed = requireNotNull(fixture.receiverTransfer())
            savedUri = completed.savedUri
            fixture.receiverStore.update { it.copy(transfers = listOf(completed.copy(savedCopyAvailability = SavedCopyAvailability.MISSING))) }
            withContext(Dispatchers.Main) { fixture.receiver.retry(completed) }
            assertEquals(SavedCopyAvailability.AVAILABLE, fixture.receiverTransfer()?.savedCopyAvailability)
            assertEquals(completed.requestId, fixture.receiverTransfer()?.requestId)
            assertEquals(1, fixture.ownerLink.filesSent)
            fixture.receiverStore.update { it.copy(transfers = listOf(completed.copy(savedUri = "content://com.thatsmyface.test.storagefixture/revoked"))) }
            withContext(Dispatchers.Main) { fixture.receiver.retry(requireNotNull(fixture.receiverTransfer())) }
            assertEquals(SavedCopyAvailability.UNREADABLE, fixture.receiverTransfer()?.savedCopyAvailability)
            assertEquals(TransferStatus.COMPLETE, fixture.receiverTransfer()?.status)
            assertEquals(completed.requestId, fixture.receiverTransfer()?.requestId)
            assertEquals(1, fixture.ownerLink.filesSent)
        } finally {
            savedUri?.let { uri -> fixture.receiverStore.update { it.copy(transfers = it.transfers.map { transfer -> transfer.copy(savedUri = uri) }) } }
            fixture.close()
        }
    }

    @Test fun eventReadyWaitsForBothPeersBeforePrivateCatalogs() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val release = CompletableDeferred<Unit>()
            fixture.ownerLink.readyBarrier = release
            fixture.pair(waitForCatalog = false)
            fixture.await { fixture.ownerLink.readyEntered }
            delay(150)
            assertTrue(fixture.ownerLink.messages.none(::isPrivate))
            assertTrue(fixture.receiverLink.messages.none(::isPrivate))
            assertTrue(fixture.receiverStore.state.value.offers.isEmpty())
            release.complete(Unit)
            fixture.await { fixture.receiverStore.state.value.offers.size == 1 }
            assertTrue(fixture.ownerLink.messages.any { it is WireMessage.Catalog })
            assertTrue(fixture.failures.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun rejectionCancellationAndLateApprovalNeverSendAnOriginal() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            val rejected = fixture.ownerTransfer()
            withContext(Dispatchers.Main) { fixture.owner.reject(rejected) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.REJECTED }
            withContext(Dispatchers.Main) { fixture.receiver.retry(requireNotNull(fixture.receiverTransfer())) }
            fixture.await { fixture.ownerTransfer().status == TransferStatus.AWAITING_APPROVAL && fixture.ownerTransfer().requestId != rejected.requestId }
            val cancelled = requireNotNull(fixture.receiverTransfer())
            withContext(Dispatchers.Main) { fixture.receiver.cancel(cancelled) }
            fixture.await { fixture.ownerTransfer().status == TransferStatus.CANCELLED }
            withContext(Dispatchers.Main) {
                fixture.ownerLink.sendMessage(fixture.receiverLink.id, WireMessage.Decision(fixture.event.id, cancelled.requestId, cancelled.photoId, true))
            }
            delay(150)
            assertEquals(TransferStatus.CANCELLED, fixture.receiverTransfer()?.status)
            assertEquals(0, fixture.ownerLink.filesSent)
            assertNull(fixture.receiverTransfer()?.savedUri)
        } finally { fixture.close() }
    }

    @Test fun interruptedOriginalWaitsThenRetriesWithFreshApproval() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            val firstRequest = fixture.ownerTransfer().requestId
            fixture.ownerLink.interruptNextFile = true
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.WAITING }
            assertNull(fixture.receiverTransfer()?.savedUri)
            fixture.pair()
            withContext(Dispatchers.Main) { fixture.receiver.retry(requireNotNull(fixture.receiverTransfer())) }
            fixture.await { fixture.ownerTransfer().status == TransferStatus.AWAITING_APPROVAL && fixture.ownerTransfer().requestId != firstRequest }
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.COMPLETE && fixture.ownerTransfer().status == TransferStatus.COMPLETE }
            assertArrayEquals(fixture.original, fixture.read(Uri.parse(fixture.receiverTransfer()!!.savedUri)))
        } finally { fixture.close() }
    }

    @Test fun corruptPayloadFailsIntegrityWithoutCompletionReceipt() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            fixture.ownerLink.corruptNextFile = true
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.FAILED }
            assertNull(fixture.receiverTransfer()?.savedUri)
            assertNotEquals(TransferStatus.COMPLETE, fixture.ownerTransfer().status)
            assertTrue(fixture.receiverLink.messages.none { it is WireMessage.Receipt })
            assertArrayEquals(fixture.original, fixture.read(fixture.source))
        } finally { fixture.close() }
    }

    @Test fun aFailedReadyAcknowledgmentCanBeRetriedWithoutStuckPayloads() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            fixture.receiverLink.failNextReady = true
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.FAILED && fixture.ownerTransfer().status == TransferStatus.FAILED }
            assertEquals(0, fixture.ownerLink.filesSent)
            assertNull(fixture.receiverTransfer()?.payloadId)
            withContext(Dispatchers.Main) { fixture.receiver.retry(requireNotNull(fixture.receiverTransfer())) }
            fixture.await { fixture.ownerTransfer().status == TransferStatus.AWAITING_APPROVAL }
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.COMPLETE && fixture.ownerTransfer().status == TransferStatus.COMPLETE }
            assertEquals(1, fixture.ownerLink.filesSent)
        } finally { fixture.close() }
    }

    @Test fun failedCancellationNoticeStillStopsTheLocalTransfer() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            fixture.ownerLink.failNextCancel = true
            withContext(Dispatchers.Main) { fixture.owner.cancel(fixture.ownerTransfer()) }
            assertEquals(TransferStatus.CANCELLED, fixture.ownerTransfer().status)
            assertFalse(fixture.ownerTransfer().approved)
            assertNull(fixture.ownerTransfer().payloadId)
            assertEquals(0, fixture.ownerLink.filesSent)
        } finally { fixture.close() }
    }

    @Test fun immediateRestartClearsOldReferencesAndLeavesInterruptedRequestsRetryable() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            fixture.receiverStore.update { state ->
                state.copy(peers = state.peers.map { it.copy(faceRefs = listOf(List(128) { index -> if (index == 0) 1f else 0f })) })
            }
            withContext(Dispatchers.Main) {
                fixture.receiver.stop()
                fixture.receiver.start(fixture.event)
            }
            assertTrue(fixture.receiverStore.state.value.peers.all { it.faceRefs.isEmpty() })
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.WAITING }
            assertNull(fixture.receiverTransfer()?.payloadId)
        } finally { fixture.close() }
    }

    @Test fun revokingAQueuedOriginalRemovesItsSnapshotAndRejectsLateReady() = runBlocking {
        assertQueuedRevocation(ownerInitiated = true)
    }

    @Test fun receiverRevocationRemovesTheOwnersQueuedSnapshot() = runBlocking {
        assertQueuedRevocation(ownerInitiated = false)
    }

    private suspend fun assertQueuedRevocation(ownerInitiated: Boolean) {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            fixture.receiverLink.deferReady = true
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.receiverLink.deferredReady != null }
            assertEquals(TransferStatus.QUEUED, fixture.ownerTransfer().status)
            val payloadId = requireNotNull(fixture.ownerTransfer().payloadId)
            val snapshots = File(fixture.ownerContext.cacheDir, "outgoing")
            assertEquals(1, snapshots.listFiles().orEmpty().count { it.isFile })

            withContext(Dispatchers.Main) {
                if (ownerInitiated) {
                    fixture.owner.revoke(fixture.event.id, requireNotNull(fixture.receiverStore.state.value.profile).id)
                } else {
                    fixture.receiver.revoke(fixture.event.id, requireNotNull(fixture.ownerStore.state.value.profile).id)
                }
            }
            fixture.await {
                fixture.receiverStore.state.value.peers.singleOrNull()?.allowed == false &&
                    fixture.ownerStore.state.value.peers.singleOrNull()?.allowed == false &&
                    payloadId in fixture.ownerLink.cancelledPayloads
            }
            withContext(Dispatchers.Main) { fixture.receiverLink.deliverDeferredReady() }

            assertEquals(TransferStatus.CANCELLED, fixture.ownerTransfer().status)
            assertEquals(TransferStatus.CANCELLED, fixture.receiverTransfer()?.status)
            assertFalse(fixture.ownerTransfer().approved)
            assertTrue(fixture.receiverStore.state.value.offers.isEmpty())
            assertEquals(0, fixture.ownerLink.filesSent)
            assertNull(fixture.receiverTransfer()?.savedUri)
            assertArrayEquals(fixture.original, fixture.read(fixture.source))
            assertEquals("Revoked outgoing copies must be removed before the session ends", 0,
                snapshots.listFiles().orEmpty().count { it.isFile })
        } finally { fixture.close() }
    }

    @Test fun matchedTransportCodesDoNotAdmitTheWrongEventSecret() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize(receiverSecret = "b".repeat(64))
            fixture.pair(waitForCatalog = false)
            fixture.await {
                fixture.ownerLink.peers.value.singleOrNull()?.status == PeerStatus.DISCONNECTED &&
                    fixture.receiverLink.peers.value.singleOrNull()?.status == PeerStatus.DISCONNECTED
            }
            assertTrue(fixture.ownerStore.state.value.peers.isEmpty())
            assertTrue(fixture.receiverStore.state.value.peers.isEmpty())
            assertTrue(fixture.ownerLink.messages.none(::isPrivate))
            assertTrue(fixture.receiverLink.messages.none(::isPrivate))
            assertTrue(fixture.receiverStore.state.value.offers.isEmpty())
            assertEquals(0, fixture.ownerLink.filesSent)
        } finally { fixture.close() }
    }

    @Test fun verifiedFriendCannotRequestAPhotoFromAnotherEvent() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val otherEvent = Event(title = "Separate fixture event", secret = "b".repeat(64))
            val foreignPhoto = fixture.ownerStore.state.value.photos.single().copy(id = newId(), eventId = otherEvent.id)
            fixture.ownerStore.update { it.copy(events = it.events + otherEvent, photos = it.photos + foreignPhoto) }
            fixture.pair()

            assertFalse(fixture.sendRequestExpectingRejection(foreignPhoto.id).approved)
            assertFalse(fixture.sendRequestExpectingRejection(newId()).approved)
            assertTrue(fixture.ownerStore.state.value.transfers.isEmpty())
            assertTrue(fixture.receiverStore.state.value.transfers.isEmpty())
            assertEquals(1, fixture.receiverStore.state.value.offers.size)
            assertNotEquals(foreignPhoto.id, fixture.receiverStore.state.value.offers.single().photoId)
            assertEquals(0, fixture.ownerLink.filesSent)
            assertArrayEquals(fixture.original, fixture.read(fixture.source))
        } finally { fixture.close() }
    }

    @Test fun staleCancellationAndDuplicateRequestsDoNotReplaceAFreshApproval() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            val previous = fixture.ownerTransfer()
            withContext(Dispatchers.Main) { fixture.receiver.cancel(requireNotNull(fixture.receiverTransfer())) }
            fixture.await { fixture.ownerTransfer().status == TransferStatus.CANCELLED }
            withContext(Dispatchers.Main) { fixture.receiver.retry(requireNotNull(fixture.receiverTransfer())) }
            fixture.await {
                fixture.ownerTransfer().status == TransferStatus.AWAITING_APPROVAL &&
                    fixture.ownerTransfer().requestId != previous.requestId
            }
            val fresh = fixture.ownerTransfer()
            withContext(Dispatchers.Main) {
                repeat(2) {
                    fixture.receiverLink.sendMessage(fixture.ownerLink.id, WireMessage.Request(fresh.eventId, fresh.requestId, fresh.photoId))
                }
                fixture.receiverLink.sendMessage(fixture.ownerLink.id, WireMessage.Cancel(previous.eventId, previous.requestId, previous.photoId))
            }
            // The denial acknowledges that the preceding byte messages have been handled.
            fixture.sendRequestExpectingRejection(newId())
            assertEquals(fresh, fixture.ownerTransfer())
            assertEquals(1, fixture.ownerStore.state.value.transfers.size)
            assertEquals(0, fixture.ownerLink.filesSent)

            withContext(Dispatchers.Main) { fixture.owner.approve(fresh) }
            fixture.await { fixture.receiverTransfer()?.status == TransferStatus.COMPLETE && fixture.ownerTransfer().status == TransferStatus.COMPLETE }
            assertEquals(1, fixture.ownerLink.filesSent)
            assertArrayEquals(fixture.original, fixture.read(Uri.parse(fixture.receiverTransfer()!!.savedUri)))
        } finally { fixture.close() }
    }

    @Test fun fileCompletionAfterCancellationRemovesStagingWithoutSavingAnOriginal() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.pair()
            fixture.request()
            fixture.ownerLink.deferFile = true
            withContext(Dispatchers.Main) { fixture.owner.approve(fixture.ownerTransfer()) }
            fixture.await { fixture.ownerLink.deferredFile != null }
            val delayed = requireNotNull(fixture.ownerLink.deferredFile)
            assertTrue(fixture.mediaExists(delayed.uri))
            assertNotEquals(TransferStatus.COMPLETE, fixture.ownerTransfer().status)

            withContext(Dispatchers.Main) { fixture.receiver.cancel(requireNotNull(fixture.receiverTransfer())) }
            fixture.await { fixture.ownerTransfer().status == TransferStatus.CANCELLED }
            withContext(Dispatchers.Main) { fixture.ownerLink.deliverDeferredFile() }
            fixture.await { !fixture.mediaExists(delayed.uri) }

            assertEquals(TransferStatus.CANCELLED, fixture.receiverTransfer()?.status)
            assertNull(fixture.receiverTransfer()?.savedUri)
            assertTrue(fixture.receiverLink.messages.none { it is WireMessage.Receipt })
            assertArrayEquals(fixture.original, fixture.read(fixture.source))
        } finally { fixture.close() }
    }

    private class Fixture {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private val testId = newId()
        val ownerContext = IsolatedContext(context, "$testId-owner")
        val receiverContext = IsolatedContext(context, "$testId-receiver")
        private val ownerKey = "thatsmyface.test.$testId.owner"
        val receiverKey = "thatsmyface.test.$testId.receiver"
        val ownerStore = LocalStore(ownerContext, ownerKey)
        val receiverStore = LocalStore(receiverContext, receiverKey)
        val failures = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> failures += error })
        private val temporaryUris = mutableListOf<Uri>()
        val event = Event(title = "Synthetic sharing fixture", secret = "a".repeat(64))
        val ownerLink = TestLink("owner-endpoint", scope, ::stage)
        val receiverLink = TestLink("receiver-endpoint", scope, ::stage)
        val owner = SharingSession(ownerContext, ownerStore, PhotoFiles(ownerContext), FaceEngine(ownerContext), scope, {}, ownerLink)
        val receiver = SharingSession(receiverContext, receiverStore, PhotoFiles(receiverContext), FaceEngine(receiverContext), scope, {}, receiverLink)
        lateinit var source: Uri
        lateinit var original: ByteArray

        suspend fun initialize(receiverSecret: String = event.secret) {
            ownerLink.other = receiverLink
            receiverLink.other = ownerLink
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff4f64e8.toInt()) }
            original = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
            bitmap.recycle()
            source = stage(original)
            val photo = PhotoFiles(ownerContext).importPhoto(event.id, source, persistPermission = false)
            ownerStore.update { it.copy(profile = Profile(nickname = "Owner"), events = listOf(event), photos = listOf(photo)) }
            val receiverEvent = event.copy(secret = receiverSecret)
            receiverStore.update { it.copy(profile = Profile(nickname = "Receiver"), events = listOf(receiverEvent)) }
            withContext(Dispatchers.Main) { owner.start(event); receiver.start(receiverEvent) }
        }

        suspend fun pair(waitForCatalog: Boolean = true) {
            withContext(Dispatchers.Main) {
                ownerLink.connect(receiverLink.id)
                ownerLink.verify(receiverLink.id)
                assertEquals(PeerStatus.VERIFYING, receiverLink.peers.value.single().status)
                receiverLink.verify(ownerLink.id)
            }
            if (waitForCatalog) await {
                owner.endpointFor(event.id, receiverStore.state.value.profile!!.id) != null &&
                    receiver.endpointFor(event.id, ownerStore.state.value.profile!!.id) != null && receiverStore.state.value.offers.size == 1
            }
        }

        suspend fun request() {
            withContext(Dispatchers.Main) { receiver.request(receiverStore.state.value.offers.single()) }
            await { ownerStore.state.value.transfers.singleOrNull()?.status == TransferStatus.AWAITING_APPROVAL }
        }

        suspend fun sendRequestExpectingRejection(photoId: String): WireMessage.Decision {
            val requestId = newId()
            withContext(Dispatchers.Main) {
                receiverLink.sendMessage(ownerLink.id, WireMessage.Request(event.id, requestId, photoId))
            }
            await { ownerLink.messages.filterIsInstance<WireMessage.Decision>().any { it.requestId == requestId } }
            return ownerLink.messages.filterIsInstance<WireMessage.Decision>().single { it.requestId == requestId }.also {
                assertFalse(it.approved)
            }
        }

        fun ownerTransfer(): Transfer = ownerStore.state.value.transfers.single()
        fun receiverTransfer(): Transfer? = receiverStore.state.value.transfers.singleOrNull()

        suspend fun await(condition: () -> Boolean) {
            try {
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) { condition() }) {
                        failures.firstOrNull()?.let { throw AssertionError("Session coroutine failed", it) }
                        delay(20)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Pair fixture timed out. Owner link=${ownerLink.peers.value.map { it.status }}, " +
                    "receiver link=${receiverLink.peers.value.map { it.status }}, " +
                    "owner messages=${ownerLink.messages.map { it::class.simpleName }}, receiver messages=${receiverLink.messages.map { it::class.simpleName }}, " +
                    "owner friends=${ownerStore.state.value.peers.size}, receiver friends=${receiverStore.state.value.peers.size}, " +
                    "owner transfers=${ownerStore.state.value.transfers.map { it.status }}, receiver transfers=${receiverStore.state.value.transfers.map { it.status }}", timeout)
            }
        }

        fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        fun mediaExists(uri: Uri): Boolean = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } == true

        private fun stage(bytes: ByteArray): Uri {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "TMF_test_${newId()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ThatsMyFaceTest/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })!!
            temporaryUris += uri
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }

        suspend fun close() {
            withContext(Dispatchers.Main) { owner.stop(); receiver.stop(); scope.cancel() }
            receiverStore.state.value.transfers.mapNotNull { it.savedUri }.forEach { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
            temporaryUris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            ownerStore.clearAll()
            receiverStore.clearAll()
            ownerContext.root.deleteRecursively()
            receiverContext.root.deleteRecursively()
        }
    }

    private class IsolatedContext(base: Context, name: String) : ContextWrapper(base) {
        val root = File(base.cacheDir, "session-tests/$name").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }

    private class TestLink(val id: String, private val scope: CoroutineScope, private val stage: (ByteArray) -> Uri) : NearbyLink {
        lateinit var other: TestLink
        override val peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
        override val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
        override val active = MutableStateFlow(false)
        val messages = CopyOnWriteArrayList<WireMessage>()
        val cancelledPayloads = mutableSetOf<Long>()
        var filesSent = 0
        var interruptNextFile = false
        var corruptNextFile = false
        var failNextReady = false
        var failNextCancel = false
        var deferReady = false
        var deferredReady: WireMessage.Ready? = null
        var deferFile = false
        var deferredFile: TransportEvent.FileReceived? = null
        var readyBarrier: CompletableDeferred<Unit>? = null
        var readyEntered = false
        private var accepted = false
        private var allowed = false
        private var receivedHello = false
        private var connectedEvents: CompletableDeferred<Unit>? = null
        private var event = ""
        private var nickname = ""
        private val originals = mutableMapOf<Long, File>()
        private val expected = mutableMapOf<Long, Long>()

        override suspend fun start(eventId: String, nickname: String) { event = eventId; this.nickname = nickname; active.value = true }
        override fun stop() { if (active.value && ::other.isInitialized) disconnect(other.id); active.value = false }
        override suspend fun connect(endpointId: String) {
            check(active.value && other.active.value && endpointId == other.id)
            accepted = false; other.accepted = false; allowed = false; other.allowed = false
            receivedHello = false; other.receivedHello = false
            peers.value = listOf(NearbyPeer(other.id, other.nickname, PeerStatus.VERIFYING, "1234"))
            other.peers.value = listOf(NearbyPeer(id, nickname, PeerStatus.VERIFYING, "1234"))
        }
        override suspend fun verify(endpointId: String) {
            check(endpointId == other.id)
            accepted = true
            if (other.accepted) {
                val connected = CompletableDeferred<Unit>()
                connectedEvents = connected
                other.connectedEvents = connected
                peers.value = peers.value.map { it.copy(status = PeerStatus.CONNECTED) }
                other.peers.value = other.peers.value.map { it.copy(status = PeerStatus.CONNECTED) }
                events.emit(TransportEvent.Connected(other.id, "1234"))
                other.events.emit(TransportEvent.Connected(id, "1234"))
                connected.complete(Unit)
            }
        }
        override fun reject(endpointId: String) = disconnect(endpointId)
        override fun disconnect(endpointId: String) {
            accepted = false; allowed = false; other.accepted = false; other.allowed = false
            peers.value = peers.value.map { it.copy(status = PeerStatus.DISCONNECTED) }
            other.peers.value = other.peers.value.map { it.copy(status = PeerStatus.DISCONNECTED) }
            scope.launch { events.emit(TransportEvent.Disconnected(other.id)); other.events.emit(TransportEvent.Disconnected(id)) }
        }
        override fun allowPeer(endpointId: String) { check(accepted); allowed = true; peers.value = peers.value.map { it.copy(status = PeerStatus.VERIFIED) } }
        override suspend fun sendMessage(endpointId: String, message: WireMessage) {
            connectedEvents?.await()
            if (message is WireMessage.Ready && failNextReady) { failNextReady = false; error("Injected acknowledgment failure") }
            if (message is WireMessage.Cancel && failNextCancel) { failNextCancel = false; error("Injected cancellation notice failure") }
            check(endpointId == other.id && accepted && other.accepted)
            if (message is WireMessage.EventReady) { readyEntered = true; readyBarrier?.await() }
            check(message is WireMessage.Hello || allowed)
            val decoded = WireCodec.decode(WireCodec.encode(message), other.event)
            check(decoded is WireMessage.Hello || (decoded is WireMessage.EventReady && other.receivedHello) || other.allowed) { "Private data arrived before event verification" }
            if (decoded is WireMessage.Hello) other.receivedHello = true
            messages += message
            if (decoded is WireMessage.Ready && deferReady) { deferredReady = decoded; return }
            other.events.emit(TransportEvent.Message(id, decoded))
        }
        suspend fun deliverDeferredReady() {
            val message = requireNotNull(deferredReady)
            deferredReady = null
            other.events.emit(TransportEvent.Message(id, message))
        }
        suspend fun deliverDeferredFile() {
            val file = requireNotNull(deferredFile)
            deferredFile = null
            other.events.emit(file)
        }
        override fun prepareFile(file: File): PreparedFile = PreparedFile(Payload.fromFile(file), null).also { originals[it.payloadId] = file }
        override fun prepareFile(descriptor: ParcelFileDescriptor): PreparedFile = error("This fixture uses selected file snapshots")
        override suspend fun sendFile(endpointId: String, prepared: PreparedFile) {
            check(allowed && other.allowed && endpointId == other.id)
            val bytes = originals.getValue(prepared.payloadId).readBytes()
            check(other.expected[prepared.payloadId] == bytes.size.toLong()) { "File sent without approved metadata acknowledgment" }
            filesSent++
            events.emit(TransportEvent.Progress(other.id, prepared.payloadId, bytes.size / 2L, bytes.size.toLong(), PayloadStatus.IN_PROGRESS))
            other.events.emit(TransportEvent.Progress(id, prepared.payloadId, bytes.size / 2L, bytes.size.toLong(), PayloadStatus.IN_PROGRESS))
            if (interruptNextFile) { interruptNextFile = false; disconnect(endpointId); error("Injected transport interruption") }
            if (corruptNextFile) { corruptNextFile = false; bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() }
            val uri = stage(bytes)
            events.emit(TransportEvent.Progress(other.id, prepared.payloadId, bytes.size.toLong(), bytes.size.toLong(), PayloadStatus.SUCCESS))
            other.events.emit(TransportEvent.Progress(id, prepared.payloadId, bytes.size.toLong(), bytes.size.toLong(), PayloadStatus.SUCCESS))
            val received = TransportEvent.FileReceived(id, prepared.payloadId, uri)
            if (deferFile) deferredFile = received else other.events.emit(received)
        }
        override fun expectFile(endpointId: String, payloadId: Long, byteCount: Long) { check(allowed && endpointId == other.id); check(expected.put(payloadId, byteCount) == null) }
        override fun cancel(payloadId: Long) { cancelledPayloads += payloadId; expected.remove(payloadId); originals.remove(payloadId) }
    }

    companion object {
        private fun isPrivate(message: WireMessage): Boolean = message !is WireMessage.Hello && message !is WireMessage.EventReady
    }
}
