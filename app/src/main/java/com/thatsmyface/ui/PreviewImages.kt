package com.thatsmyface.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thatsmyface.AppModel
import com.thatsmyface.data.Photo
import com.thatsmyface.data.PhotoAvailability
import com.thatsmyface.data.Transfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val previewLoads = Semaphore(3)

@Composable
internal fun PhotoGrid(content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun PhotoTile(description: String, label: String, detail: String, onClick: () -> Unit,
    preview: @Composable (Modifier) -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))) {
        preview(Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun PhotoDetails(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.9f),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
internal fun LocalPhotoPreview(model: AppModel, photo: Photo, modifier: Modifier, detailed: Boolean = false) {
    LoadedPreview(photo.uri, photo.availability, modifier, detailed,
        if (photo.availability == PhotoAvailability.AVAILABLE) "Preview unavailable" else "Check photo access") {
        model.files.thumbnail(photo)
    }
}

@Composable
internal fun SavedPhotoPreview(model: AppModel, transfer: Transfer, modifier: Modifier, detailed: Boolean = false) {
    LoadedPreview(transfer.savedUri, transfer.savedCopyAvailability, modifier, detailed, "Preview unavailable") {
        model.files.savedThumbnail(transfer)
    }
}

@Composable
internal fun OfferPreview(encoded: String?, modifier: Modifier, detailed: Boolean = false) {
    LoadedPreview(encoded, null, modifier, detailed, "No shared preview") { encoded }
}

@Composable
private fun LoadedPreview(key: Any?, version: Any?, modifier: Modifier, detailed: Boolean,
    unavailable: String, load: suspend () -> String?) {
    val preview by produceState(PreviewState(), key, version) {
        value = PreviewState()
        val image = withContext(Dispatchers.IO) {
            previewLoads.withPermit {
                try { load()?.let(::decodePreview) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
        value = PreviewState(image, false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = preview.image
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = if (detailed) ContentScale.Fit else ContentScale.Crop)
        else if (preview.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Text(unavailable, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class PreviewState(val image: ImageBitmap? = null, val loading: Boolean = true)

internal fun decodePreview(encoded: String): ImageBitmap? = runCatching {
    require(encoded.length <= 24_576)
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    require(bytes.size <= 18_432)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    // Compressed size alone cannot prevent a peer from sending an image that expands too far.
    require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })?.asImageBitmap()
}.getOrNull()
