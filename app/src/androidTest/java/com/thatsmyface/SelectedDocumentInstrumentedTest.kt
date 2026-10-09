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
import kotlinx.coroutines.withTimeoutOrNull
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
            fixture.assertFixtureAccessRevoked()
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
        private var lastTouch = "none"

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

        suspend fun assertFixtureAccessRevoked() {
            try {
                withTimeout(5_000) {
                    while (hasReadGrant(document) || mapped?.let(::hasReadGrant) == true) delay(50)
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("Fixture grants remain after revocation: document=${hasReadGrant(document)}, " +
                    "mapped=${mapped?.let(::hasReadGrant)}, persisted=" +
                    resolver.persistedUriPermissions.any { it.uri == document && it.isReadPermission }, error)
            }
            assertDenied(document)
            mapped?.let(::assertDenied)
        }

        private fun hasReadGrant(uri: Uri): Boolean = context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

        fun readExternalOriginal(): ByteArray = Base64.decode(shell("base64 $externalPath"), Base64.DEFAULT)

        suspend fun close() {
            automation.dropShellPermissionIdentity()
            try {
                finishPickerHost()
            } finally {
                revokeFixtureAccess()
                if (::model.isInitialized) withContext(Dispatchers.Main) { model.stopSharing(); model.viewModelScope.cancel() }
                store.clearAll()
                media?.let { shell("content delete --uri $it") }
                shell("rm -f -- $externalPath")
                shell("rmdir -- $externalDirectory")
                root.deleteRecursively()
            }
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
            val initialFolderVisible = withTimeoutOrNull(3_000) {
                while (automation.rootInActiveWindow?.let { listTouchTarget(it, "dir_list", folderName) } == null) delay(100)
                true
            } == true
            if (!initialFolderVisible) openImagesRoot()
            awaitPickerNode("opening Images") { listTouchTarget(it, "dir_list", folderName) }
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("List view")
                ?.firstOrNull { it.isVisibleToUser }?.let { toggle ->
                    touch(toggle)
                    awaitPickerNode("switching to list view") { root ->
                        root.findAccessibilityNodeInfosByText("Grid view").firstOrNull { it.isVisibleToUser }
                    }
                }
            touch(awaitPickerNode("opening Images and locating the fixture folder") { listTouchTarget(it, "dir_list", folderName) })
            touch(awaitPickerNode("opening the fixture folder and locating its photo") { listTouchTarget(it, "dir_list", name) })
            try {
                withTimeout(20_000) {
                    var confirmed = false
                    while (context.checkUriPermission(document, Process.myPid(), Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                        if (!confirmed) {
                            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Select")
                                ?.firstOrNull { it.text?.toString()?.equals("Select", ignoreCase = true) == true && it.isVisibleToUser && it.isEnabled }
                                ?.let { touch(it); confirmed = true }
                        }
                        delay(100)
                    }
                }
            } catch (error: TimeoutCancellationException) {
                val root = automation.rootInActiveWindow
                val controls = listOf("Open", "OPEN", "Select", "SELECT", "Choose", "Done", "List view", "Grid view", "Show roots")
                    .filter { root?.findAccessibilityNodeInfosByText(it)?.isNotEmpty() == true }
                val fixtureVisible = root?.findAccessibilityNodeInfosByText(name)?.any { it.isVisibleToUser } == true
                val folderVisible = root?.findAccessibilityNodeInfosByText(folderName)?.any { it.isVisibleToUser } == true
                throw AssertionError("System picker did not return the selected document grant: package=${root?.packageName}, " +
                    "controls=$controls, fixture=$fixtureVisible, folder=$folderVisible, touch=$lastTouch", error)
            }
        }

        private suspend fun openImagesRoot() {
            val navigation = awaitPickerNode("locating the roots drawer") { root ->
                listTouchTarget(root, "dir_list", folderName)
                    ?: visibleResource(root, "roots_list")
                    ?: root.findAccessibilityNodeInfosByText("Show roots").firstOrNull { it.isVisibleToUser }
                    ?: root.findAccessibilityNodeInfosByText("Show navigation drawer").firstOrNull { it.isVisibleToUser }
            }
            if (enclosingListRow(navigation, "dir_list") != null) return
            if (navigation.viewIdResourceName?.endsWith(":id/roots_list") != true) touch(navigation)
            val populated = awaitPickerNode("populating the roots drawer") { root ->
                listTouchTarget(root, "dir_list", folderName)
                    ?: visibleResource(root, "roots_list")?.takeIf { it.childCount > 0 }
            }
            if (enclosingListRow(populated, "dir_list") != null) return

            var scrolls = 0
            for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                for (attempt in 0 until 8) {
                    val root = automation.rootInActiveWindow
                    if (root != null) {
                        if (listTouchTarget(root, "dir_list", folderName) != null) return
                        val images = listTouchTarget(root, "roots_list", "Images")
                        if (images != null) {
                            touch(awaitPickerNode("locating the Images root") { listTouchTarget(it, "roots_list", "Images") })
                            return
                        }
                        val roots = visibleResource(root, "roots_list") ?: break
                        if (!roots.performAction(direction)) break
                        scrolls++
                    }
                    delay(250)
                }
            }
            val root = automation.rootInActiveWindow
            if (root != null) {
                if (listTouchTarget(root, "dir_list", folderName) != null) return
                listTouchTarget(root, "roots_list", "Images")?.let { touch(it); return }
            }
            val roots = root?.let { visibleResource(it, "roots_list") }
            val knownRoots = listOf("Recent", "Images", "Downloads").filter {
                roots?.findAccessibilityNodeInfosByText(it)?.any { node -> node.isVisibleToUser } == true
            }
            throw AssertionError("System picker could not locate Images: rootsVisible=${roots != null}, " +
                "children=${roots?.childCount ?: 0}, scrolls=$scrolls, knownRoots=$knownRoots")
        }

        private suspend fun awaitPickerNode(stage: String, find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
            var activePackage = "none"
            try {
                return withTimeout(20_000) {
                    var found: AccessibilityNodeInfo? = null
                    while (found == null) {
                        automation.rootInActiveWindow?.let { root ->
                            activePackage = root.packageName?.toString() ?: activePackage
                            found = find(root)?.takeIf { it.isVisibleToUser && it.isEnabled }
                        }
                        if (found != null) {
                            automation.waitForIdle(300, 5_000)
                            found = automation.rootInActiveWindow?.let(find)?.takeIf { it.isVisibleToUser && it.isEnabled }
                        }
                        if (found == null) delay(100)
                    }
                    requireNotNull(found)
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("System picker timed out while $stage (package=$activePackage)", error)
            }
        }

        private fun visibleResource(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
            root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$id").firstOrNull { it.isVisibleToUser }

        private fun listTouchTarget(root: AccessibilityNodeInfo, listId: String, label: String): AccessibilityNodeInfo? {
            val list = visibleResource(root, listId) ?: return null
            for (candidate in list.findAccessibilityNodeInfosByText(label.removeSuffix(".jpg"))) {
                val matches = candidate.text?.toString() in listOf(label, label.removeSuffix(".jpg")) ||
                    candidate.contentDescription?.contains(label) == true
                if (!matches) continue
                val row = enclosingListRow(candidate, listId) ?: continue
                val title = row.findAccessibilityNodeInfosByViewId("android:id/title").firstOrNull { it.isVisibleToUser }
                // Photo grids expose the filename on the tile and its separate preview button.
                return title ?: row.takeIf { it.isVisibleToUser }
            }
            return null
        }

        private fun enclosingListRow(start: AccessibilityNodeInfo, listId: String): AccessibilityNodeInfo? {
            var node: AccessibilityNodeInfo? = start
            while (node != null) {
                val parent = node.parent ?: return null
                if (parent.viewIdResourceName?.endsWith(":id/$listId") == true) return node
                node = parent
            }
            return null
        }

        private fun touch(node: AccessibilityNodeInfo) {
            val bounds = Rect().also(node::getBoundsInScreen)
            assertTrue("The picker touch target must be visible", node.isVisibleToUser && !bounds.isEmpty)
            lastTouch = "${node.className}:${node.viewIdResourceName}:$bounds"
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }

        private suspend fun finishPickerHost() {
            val activity = pickerHost ?: return
            withContext(Dispatchers.Main) { activity.finish() }
            try {
                withTimeout(10_000) {
                    while (!withContext(Dispatchers.Main) { activity.isDestroyed }) delay(50)
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("The picker host did not finish destruction", error)
            }
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
