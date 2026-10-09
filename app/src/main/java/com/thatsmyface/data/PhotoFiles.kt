package com.thatsmyface.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.PermissionChecker
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PhotoAccessException(val availability: PhotoAvailability, message: String) : Exception(message)

class PhotoFiles(private val context: Context) {
    private val resolver = context.contentResolver
    private val saveMutex = Mutex()

    suspend fun importPhoto(eventId: String, uri: Uri, persistPermission: Boolean = true): Photo =
        withContext(Dispatchers.IO) {
            protectAccess {
                requireContentUri(uri)
                if (persistPermission) persistReadPermission(uri)
                val mime = when (val reported = resolver.getType(uri)?.lowercase()) {
                    "image/jpg" -> "image/jpeg"
                    else -> reported
                }
                require(mime in IMAGE_EXTENSIONS) { "Choose a JPEG, PNG, WebP, HEIC, HEIF, or AVIF photo." }
                verifyImage { open(uri) }
                val digest = open(uri).use { digest(it) }
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }?.replace(Regex("[\\p{Cntrl}/\\\\]"), "")?.take(120)?.takeIf { it.isNotBlank() } ?: "Selected photo"
                Photo(eventId = eventId, uri = uri.toString(), displayName = name, mimeType = requireNotNull(mime),
                    sha256 = digest.sha256, size = digest.size)
            }
        }

    suspend fun listFolder(uri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        protectAccess {
            requireContentUri(uri)
            require(DocumentsContract.isTreeUri(uri)) { "Choose an event folder using the folder picker." }
            persistReadPermission(uri)
            val result = mutableListOf<Uri>()
            val pending = ArrayDeque<String>()
            val visited = mutableSetOf<String>()
            pending.add(DocumentsContract.getTreeDocumentId(uri))
            while (pending.isNotEmpty()) {
                val documentId = pending.removeFirst()
                if (!visited.add(documentId)) continue
                require(visited.size <= MAX_FOLDER_ITEMS) { "Choose a smaller event folder with at most 2,000 items." }
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(0)
                        val mime = cursor.getString(1)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(childId)
                        } else if (mime in IMAGE_EXTENSIONS) {
                            result += DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                        }
                        require(result.size + pending.size <= MAX_FOLDER_ITEMS) {
                            "Choose a smaller event folder with at most 2,000 items."
                        }
                    }
                } ?: throw FileNotFoundException()
            }
            result
        }
    }

    suspend fun checkAvailability(photo: Photo): Photo = withContext(Dispatchers.IO) {
        try {
            protectAccess {
                val current = open(Uri.parse(photo.uri)).use { digest(it) }
                if (current.sha256 != photo.sha256 || current.size != photo.size) {
                    throw PhotoAccessException(PhotoAvailability.CHANGED, "This photo changed. Select it again to share its current original.")
                }
            }
            photo.copy(availability = PhotoAvailability.AVAILABLE, error = null)
        } catch (error: PhotoAccessException) {
            photo.copy(availability = error.availability, error = error.message)
        }
    }

    suspend fun outgoingSnapshot(photo: Photo): File = withContext(Dispatchers.IO) {
        protectAccess {
            val directory = File(context.cacheDir, "outgoing").apply { mkdirs() }
            val temporary = File.createTempFile("photo-", ".tmp", directory)
            try {
                val copied = open(Uri.parse(photo.uri)).use { input -> temporary.outputStream().use { digest(input, it) } }
                if (copied.sha256 != photo.sha256 || copied.size != photo.size) {
                    throw PhotoAccessException(PhotoAvailability.CHANGED, "This photo changed. Select it again before sharing.")
                }
                temporary
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        }
    }

    suspend fun thumbnail(photo: Photo): String? = withContext(Dispatchers.IO) {
        protectAccess {
            val uri = Uri.parse(photo.uri)
            requireContentUri(uri)
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0 && width.toLong() * height <= 120_000_000)
                val scale = minOf(1.0, 192.0 / maxOf(width, height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
            }
            try {
                var preview: String? = null
                for (quality in listOf(75, 60, 45)) {
                    val bytes = ByteArrayOutputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
                        it.toByteArray()
                    }
                    if (bytes.size <= 18_432) {
                        preview = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        break
                    }
                }
                preview
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun saveReceived(file: File, transfer: Transfer, canPublish: () -> Boolean = { true }): Uri = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            check(canPublish()) { "Download cancelled or access removed." }
            require(transfer.direction == TransferDirection.RECEIVE) { "Only received photos can be saved." }
            require(transfer.mimeType in IMAGE_EXTENSIONS) { "Unsupported image type." }
            verifyIntegrity(file, transfer.size, transfer.sha256)
            verifyImage { file.inputStream() }
            val name = "TMF_${transfer.key}_${transfer.sha256.take(16)}.${IMAGE_EXTENSIONS.getValue(transfer.mimeType)}"
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existing = findSaved(collection, name)
            if (existing != null) {
                val valid = runCatching { open(existing.first).use { digest(it) } }.getOrNull()
                if (valid?.sha256 == transfer.sha256 && valid.size == transfer.size) {
                    check(canPublish()) { "Download cancelled or access removed." }
                    publish(existing.first)
                    return@withLock existing.first
                }
                check(existing.second) { "A previously saved copy changed. Keep it safe and remove it manually before retrying." }
                resolver.delete(existing.first, null, null)
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, transfer.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, DESTINATION)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values) ?: error("The phone could not create a photo. Check available storage.")
            try {
                resolver.openOutputStream(target, "w")?.use { output ->
                    file.inputStream().use { input ->
                        val copied = digest(input, output)
                        check(copied.sha256 == transfer.sha256 && copied.size == transfer.size) { "Photo integrity check failed. Ask your friend to retry." }
                    }
                } ?: error("The phone could not write the photo. Check available storage.")
                val written = open(target).use { digest(it) }
                check(written.sha256 == transfer.sha256 && written.size == transfer.size) { "Saved photo integrity check failed. Retry the download." }
                check(canPublish()) { "Download cancelled or access removed." }
                publish(target)
                target
            } catch (error: Exception) {
                resolver.delete(target, null, null)
                throw error
            }
        }
    }

    suspend fun checkSavedCopy(transfer: Transfer): SavedCopyCheck = withContext(Dispatchers.IO) {
        require(transfer.direction == TransferDirection.RECEIVE && transfer.status == TransferStatus.COMPLETE)
        val unavailable = SavedCopyCheck(SavedCopyAvailability.UNREADABLE,
            "The saved copy could not be checked. Restore photo access or reconnect storage, then check again.")
        val uri = transfer.savedUri?.let(Uri::parse) ?: return@withContext unavailable
        try {
            requireContentUri(uri)
            val exists = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() }
                ?: return@withContext unavailable
            if (!exists) return@withContext SavedCopyCheck(SavedCopyAvailability.MISSING,
                "The saved copy is missing. You can request the original again with your friend's approval.")
            val actual = open(uri).use { digest(it) }
            if (actual.sha256 == transfer.sha256 && actual.size == transfer.size) SavedCopyCheck(SavedCopyAvailability.AVAILABLE)
            else SavedCopyCheck(SavedCopyAvailability.CHANGED,
                "This saved copy changed. Keep it safe. Restore or remove it yourself, then check again before requesting the original.")
        } catch (_: SecurityException) {
            unavailable
        } catch (_: PhotoAccessException) {
            unavailable
        } catch (_: java.io.IOException) {
            unavailable
        } catch (_: IllegalArgumentException) {
            unavailable
        }
    }

    suspend fun releasePermissions() = withContext(Dispatchers.IO) {
        resolver.persistedUriPermissions.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun open(uri: Uri): InputStream {
        return try {
            val media = mediaUri(uri)
            if (media == null) {
                if (uri.authority in SYSTEM_DOCUMENT_PROVIDERS) withOriginalMetadataAccess {
                    resolver.openInputStream(uri) ?: throw FileNotFoundException()
                } else resolver.openInputStream(uri) ?: throw FileNotFoundException()
            }
            else try {
                openMedia(MediaStore.setRequireOriginal(media))
            } catch (error: SecurityException) {
                // Exact document grants do not cover the query added by setRequireOriginal.
                if (!isRegularMediaRow(media)) throw error
                withOriginalMetadataAccess { openMedia(media) }
            }
        } catch (_: UnsupportedOperationException) {
            throw originalMetadataError()
        }
    }

    private inline fun withOriginalMetadataAccess(open: () -> InputStream): InputStream {
        requireOriginalMetadataAccess()
        val input = open()
        return try {
            requireOriginalMetadataAccess()
            input
        } catch (error: Exception) {
            input.close()
            throw error
        }
    }

    private fun openMedia(uri: Uri): InputStream {
        val options = Bundle().apply {
            if (Build.VERSION.SDK_INT >= 31) putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
        }
        val descriptor = resolver.openTypedAssetFileDescriptor(uri, "*/*", options) ?: throw FileNotFoundException()
        return try { descriptor.createInputStream() }
        catch (error: Exception) { descriptor.close(); throw error }
    }

    private fun requireOriginalMetadataAccess() {
        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PermissionChecker.PERMISSION_GRANTED) {
            throw originalMetadataError()
        }
    }

    private fun isRegularMediaRow(uri: Uri): Boolean {
        val path = uri.pathSegments
        val collection = path.drop(1).dropLast(1)
        return uri.authority == MediaStore.AUTHORITY && uri.query == null && uri.fragment == null &&
            path.lastOrNull()?.toLongOrNull() != null &&
            (collection == listOf("images", "media") || collection == listOf("file"))
    }

    private fun mediaUri(uri: Uri): Uri? {
        requireContentUri(uri)
        return when {
            uri.authority == MediaStore.AUTHORITY -> uri
            // Android 10 and 11 route media-document conversion to the wrong provider.
            Build.VERSION.SDK_INT >= 31 && uri.authority in SYSTEM_DOCUMENT_PROVIDERS -> MediaStore.getMediaUri(context, uri)
            else -> null
        }
    }

    private fun originalMetadataError() = PhotoAccessException(PhotoAvailability.PERMISSION_REVOKED,
        "Allow original photo metadata in Photos before importing or sharing. This keeps original bytes, including location metadata.")

    @Suppress("DEPRECATION")
    private fun findSaved(collection: Uri, name: String): Pair<Uri, Boolean>? {
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ?"
        return resolver.query(MediaStore.setIncludePending(collection),
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING), selection,
            arrayOf(name, DESTINATION, context.packageName), null)?.use { cursor ->
            if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()) to (cursor.getInt(1) == 1) else null
        }
    }

    private fun publish(uri: Uri) {
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
            "The phone could not finish saving the photo. Retry the download."
        }
    }

    private fun requireContentUri(uri: Uri) {
        require(uri.scheme == "content") { "Choose photos using the Android photo or folder picker." }
    }

    private fun verifyImage(openStream: () -> InputStream) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 120_000_000) {
            "Choose a supported photo smaller than 120 megapixels."
        }
    }

    private inline fun <T> protectAccess(block: () -> T): T = try {
        block()
    } catch (error: PhotoAccessException) {
        throw error
    } catch (_: SecurityException) {
        throw PhotoAccessException(PhotoAvailability.PERMISSION_REVOKED, "Access was removed. Select this photo or folder again.")
    } catch (_: FileNotFoundException) {
        throw PhotoAccessException(PhotoAvailability.MISSING, "This photo or folder is missing. Select it again if it moved.")
    } catch (_: java.io.IOException) {
        throw PhotoAccessException(PhotoAvailability.UNREADABLE, "This photo cannot be read. Make sure it is stored on this phone and try again.")
    } catch (_: UnsupportedOperationException) {
        throw originalMetadataError()
    } catch (_: IllegalArgumentException) {
        throw PhotoAccessException(PhotoAvailability.UNREADABLE, "Choose a supported photo smaller than 100 MB and 120 megapixels using the Android picker.")
    }

    private companion object {
        const val DESTINATION = "Pictures/ThatsMyFace/"
        const val MAX_FOLDER_ITEMS = 2_000
        val SYSTEM_DOCUMENT_PROVIDERS = setOf("com.android.providers.media.documents", "com.android.externalstorage.documents")
        val IMAGE_EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp",
            "image/heic" to "heic", "image/heif" to "heif", "image/avif" to "avif")
    }
}

internal data class PhotoDigest(val sha256: String, val size: Long)

internal fun digest(input: InputStream, output: OutputStream? = null): PhotoDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        size += read
        require(size <= MAX_PHOTO_BYTES) { "Photos must be no larger than 100 MB." }
        digest.update(buffer, 0, read)
        output?.write(buffer, 0, read)
    }
    require(size > 0) { "This photo is empty." }
    return PhotoDigest(digest.digest().hex(), size)
}

fun verifyIntegrity(file: File, expectedSize: Long, expectedSha256: String) {
    require(expectedSize in 1..MAX_PHOTO_BYTES && expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid photo metadata." }
    require(file.length() == expectedSize) { "Photo is incomplete. Reconnect and retry." }
    val actual = file.inputStream().use { digest(it) }
    require(actual.size == expectedSize && actual.sha256 == expectedSha256) { "Photo integrity check failed. Reconnect and retry." }
}
