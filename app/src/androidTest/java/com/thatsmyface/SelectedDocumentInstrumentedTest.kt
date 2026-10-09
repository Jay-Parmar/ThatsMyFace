package com.thatsmyface

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.thatsmyface.data.LocalStore
import com.thatsmyface.data.PhotoAccessException
import com.thatsmyface.data.PhotoAvailability
import com.thatsmyface.data.newId
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SelectedDocumentInstrumentedTest {
    @Test fun exactSystemDocumentGrantPreservesOriginalAndRevocationStopsSharing() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val model = fixture.model
            fixture.act { model.saveProfile("Selected fixture") }
            fixture.act { model.createEvent("Document grant test") }
            fixture.act { model.importPhotos(listOf(fixture.document)) }
            assertEquals(model.message.value, 1, model.state.value.photos.size)
            val photo = model.state.value.photos.single()
            assertEquals(fixture.original.size.toLong(), photo.size)
            assertEquals(sha256(fixture.original), photo.sha256)
            fixture.act { model.importPhotos(listOf(fixture.document, fixture.document)) }
            assertEquals(listOf(photo.id), model.state.value.photos.map { it.id })

            val preview = Base64.decode(requireNotNull(model.files.thumbnail(photo)), Base64.NO_WRAP)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(preview, 0, preview.size))
            assertTrue(bitmap.width in 1..192 && bitmap.height in 1..192)
            bitmap.recycle()
            val snapshot = model.files.outgoingSnapshot(photo)
            try {
                assertArrayEquals(fixture.original, snapshot.readBytes())
                val coordinates = FloatArray(2)
                assertTrue(ExifInterface(snapshot.absolutePath).getLatLong(coordinates))
                assertEquals(12.5f, coordinates[0], 0.0001f)
                assertEquals(45.25f, coordinates[1], 0.0001f)
            } finally { snapshot.delete() }
            fixture.act { model.toggleTag(photo, model.state.value.profile!!.id) }
            assertEquals(1, model.state.value.offers.size)

            fixture.revokeFixtureAccess()
            assertEquals(PhotoAvailability.PERMISSION_REVOKED, model.files.checkAvailability(photo).availability)
            try {
                model.files.outgoingSnapshot(photo)
                fail("Revoked document access must not prepare an original")
            } catch (error: PhotoAccessException) {
                assertEquals(PhotoAvailability.PERMISSION_REVOKED, error.availability)
            }
            assertEquals(0, File(fixture.application.cacheDir, "outgoing").listFiles().orEmpty().size)
            fixture.act { model.rescan() }
            assertEquals(PhotoAvailability.PERMISSION_REVOKED, model.state.value.photos.single().availability)
            assertTrue(model.state.value.offers.isEmpty())
            assertArrayEquals(fixture.original, fixture.readExternalOriginal())
        } finally { fixture.close() }
    }

    private class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val automation = instrumentation.uiAutomation
        private val context = instrumentation.targetContext
        private val resolver = context.contentResolver
        private val identifier = newId()
        private val folderName = "TMF_selected_$identifier"
        private val name = "TMF_original_$identifier.jpg"
        private val externalDirectory = "/sdcard/Pictures/$folderName"
        private val externalPath = "$externalDirectory/$name"
        private val root = File(context.cacheDir, "selected-document-tests/$identifier").apply { mkdirs() }
        val application = IsolatedApplication(context, root)
        private val store = LocalStore(application, "thatsmyface.test.document.$identifier")
        lateinit var original: ByteArray
        lateinit var document: Uri
        lateinit var model: AppModel
        private var media: Uri? = null
        private var mapped: Uri? = null
        private var pickerHost: Activity? = null

        suspend fun initialize() {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            original = makeOriginal()
            shell("mkdir -p $externalDirectory")
            val encoded = Base64.encodeToString(original, Base64.NO_WRAP)
            // UiAutomation tokenizes arguments before sh sees them, so its script must be one token.
            shell("sh -c echo\${IFS}$encoded|base64\${IFS}-d>$externalPath")
            assertArrayEquals("The shell must create the complete external fixture", original, readExternalOriginal())
            automation.adoptShellPermissionIdentity()
            val source = try {
                val scanned = CompletableDeferred<Uri>()
                MediaScannerConnection.scanFile(context, arrayOf(externalPath), arrayOf("image/jpeg")) { _, uri ->
                    if (uri != null) scanned.complete(uri)
                    else scanned.completeExceptionally(AssertionError("The synthetic JPEG could not be scanned"))
                }
                val scannedSource = withTimeout(15_000) { scanned.await() }
                media = scannedSource
                val fileRow = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), scannedSource.lastPathSegment)
                resolver.query(fileRow, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.IS_PENDING, MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertNotEquals("The fixture must not be owned by this app", context.packageName, it.getString(0))
                    assertEquals("The scanned fixture must be a JPEG", "image/jpeg", it.getString(1))
                    assertEquals("The scanned fixture must be published", 0, it.getInt(2))
                    assertEquals("The scanned fixture must be indexed as an image", MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, it.getInt(3))
                    assertEquals("The scanner must read the fixture width", 32, it.getInt(4))
                    assertEquals("The scanner must read the fixture height", 24, it.getInt(5))
                    assertEquals("The scanner must read the complete fixture", original.size.toLong(), it.getLong(6))
                }
                scannedSource
            } finally { automation.dropShellPermissionIdentity() }
            assertDenied(source)
            document = DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "image:${source.lastPathSegment}")
            assertDenied(document)

            if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Runtime revocation kills instrumentation; the runner may restore this after the test finishes.
                automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
            selectThroughSystemPicker()
            resolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            finishPickerHost()
            assertArrayEquals(original, resolver.openInputStream(document)!!.use { it.readBytes() })
            if (Build.VERSION.SDK_INT >= 31) {
                val exactMedia = requireNotNull(MediaStore.getMediaUri(context, document))
                mapped = exactMedia
                assertArrayEquals(original, resolver.openInputStream(exactMedia)!!.use { it.readBytes() })
                assertDenied(MediaStore.setRequireOriginal(exactMedia))
            }
            model = withContext(Dispatchers.Main) { AppModel(application, store) }
            withTimeout(10_000) { model.ready.first { it } }
        }

        suspend fun act(action: () -> Unit) {
            withContext(Dispatchers.Main) { action() }
            withTimeout(20_000) { model.busy.first { !it } }
        }

        fun revokeFixtureAccess() {
            if (::document.isInitialized) {
                resolver.persistedUriPermissions.filter { it.uri == document }.forEach {
                    resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.revokeUriPermission(context.packageName, document, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            mapped?.let { context.revokeUriPermission(context.packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        fun readExternalOriginal(): ByteArray = Base64.decode(shell("base64 $externalPath"), Base64.DEFAULT)

        suspend fun close() {
            automation.dropShellPermissionIdentity()
            finishPickerHost()
            revokeFixtureAccess()
            if (::model.isInitialized) withContext(Dispatchers.Main) { model.stopSharing(); model.viewModelScope.cancel() }
            store.clearAll()
            media?.let { shell("content delete --uri $it") }
            shell("rm -f -- $externalPath")
            shell("rmdir -- $externalDirectory")
            root.deleteRecursively()
        }

        @Suppress("DEPRECATION")
        private suspend fun selectThroughSystemPicker() {
            pickerHost = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withContext(Dispatchers.Main) {
                pickerHost!!.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/jpeg"
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildRootUri("com.android.providers.media.documents", "images_root"))
                }, 4107)
            }
            var rootSeen = false
            var folderSeen = false
            var folderOpened = false
            var fixtureSeen = false
            var fixtureClicked = false
            var rootsOpened = false
            var imagesOpened = false
            var activePackage = "none"
            var matchCount = 0
            var exactMatchCount = 0
            val controlsSeen = mutableSetOf<String>()
            try {
                withTimeout(30_000) {
                    while (!fixtureClicked) {
                        val rootNode = automation.rootInActiveWindow
                        rootSeen = rootSeen || rootNode != null
                        activePackage = rootNode?.packageName?.toString() ?: activePackage
                        for (control in listOf("Images", "Recent", "Allow", "No items")) {
                            if (rootNode?.findAccessibilityNodeInfosByText(control)?.isNotEmpty() == true) controlsSeen += control
                        }
                        if (!imagesOpened) {
                            val imagesRoot = rootNode?.findAccessibilityNodeInfosByText("Images")
                                ?.firstOrNull { it.text?.toString() == "Images" && isRootEntry(it) }
                            if (imagesRoot != null) imagesOpened = click(imagesRoot)
                            else if (!rootsOpened) {
                                val showRoots = rootNode?.findAccessibilityNodeInfosByText("Show roots")?.firstOrNull()
                                    ?: rootNode?.findAccessibilityNodeInfosByText("Show navigation drawer")?.firstOrNull()
                                if (showRoots != null) rootsOpened = click(showRoots)
                            }
                            delay(100)
                            continue
                        }
                        val candidates = rootNode?.findAccessibilityNodeInfosByText(name.removeSuffix(".jpg")).orEmpty()
                        val exactMatches = candidates.filter { it.text?.toString() in listOf(name, name.removeSuffix(".jpg")) }
                        matchCount = maxOf(matchCount, candidates.size)
                        exactMatchCount = maxOf(exactMatchCount, exactMatches.size)
                        val fileNode = exactMatches.firstOrNull()
                            ?: candidates.firstOrNull { it.contentDescription?.contains(name) == true }
                        if (fileNode != null) {
                            fixtureSeen = true
                            fixtureClicked = click(fileNode)
                        }
                        if (!folderOpened && !fixtureClicked) {
                            val folder = rootNode?.findAccessibilityNodeInfosByText(folderName)
                                ?.firstOrNull { (it.text?.toString() == folderName || it.contentDescription?.contains(folderName) == true) && isFileListEntry(it) }
                            if (folder != null) {
                                folderSeen = true
                                folderOpened = click(folder)
                            }
                        }
                        delay(100)
                    }
                    while (context.checkUriPermission(document, Process.myPid(), Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) delay(100)
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("System picker timed out: root=$rootSeen, folder=$folderSeen, " +
                    "folderOpened=$folderOpened, fixture=$fixtureSeen, fixtureClicked=$fixtureClicked, " +
                    "rootsOpened=$rootsOpened, imagesOpened=$imagesOpened, " +
                    "package=$activePackage, controls=$controlsSeen, matches=$matchCount, exactMatches=$exactMatchCount", error)
            }
        }

        private fun isRootEntry(start: AccessibilityNodeInfo): Boolean {
            var node = start.parent
            while (node != null) {
                if (node.viewIdResourceName?.endsWith(":id/roots_list") == true) return true
                node = node.parent
            }
            return false
        }

        private fun isFileListEntry(start: AccessibilityNodeInfo): Boolean {
            var node = start.parent
            while (node != null) {
                val className = node.className?.toString().orEmpty()
                val resourceId = node.viewIdResourceName.orEmpty()
                if (className.endsWith("Toolbar") || resourceId.contains("breadcrumb") || resourceId.contains("toolbar")) return false
                if (className.endsWith("RecyclerView") || className.endsWith("GridView") || className.endsWith("ListView")) return true
                node = node.parent
            }
            return false
        }

        private fun click(start: AccessibilityNodeInfo): Boolean {
            var node: AccessibilityNodeInfo? = start
            var depth = 0
            while (node != null && depth++ < 6) {
                val current = node
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                node = current.parent
            }
            val bounds = Rect().also(start::getBoundsInScreen)
            if (!start.isVisibleToUser || bounds.isEmpty) return false
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            return true
        }

        private suspend fun finishPickerHost() {
            pickerHost?.let { withContext(Dispatchers.Main) { it.finish() } }
            pickerHost = null
            instrumentation.waitForIdleSync()
        }

        private fun assertDenied(uri: Uri) {
            try {
                resolver.openInputStream(uri)?.close()
                fail("Fixture access must require its exact selected document grant")
            } catch (_: SecurityException) { }
        }

        private fun makeOriginal(): ByteArray {
            val file = File(root, "synthetic-original.jpg")
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(32, 96, 160))
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,30/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "45/1,15/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                setAttribute(ExifInterface.TAG_USER_COMMENT, "Synthetic selected-document regression fixture")
                saveAttributes()
            }
            return file.readBytes()
        }

        private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
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

    private companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
