package com.thatsmyface.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.thatsmyface.AppModel
import com.thatsmyface.Invitations
import com.thatsmyface.data.AppState
import com.thatsmyface.data.Event
import com.thatsmyface.recognition.PhotoDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EventsPage(model: AppModel, state: AppState, selected: Event?, busy: Boolean, openPhotos: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf("") }
    var qrEvent by remember { mutableStateOf<Event?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var cameraExplanation by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(model::joinEvent) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan your friend's event invitation").setBeepEnabled(false).setBarcodeImageEnabled(false))
        else model.notify("Camera access is off. Choose a saved QR image or paste the invitation instead.")
    }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            decoding = true
            scope.launch {
                try {
                    val decoded = withContext(Dispatchers.Default) { decodeInvitation(context.contentResolver, it) }
                    model.joinEvent(decoded)
                } catch (_: Exception) {
                    model.notify("No readable event QR found. Try a clearer image or paste the invitation.")
                } finally { decoding = false }
            }
        }
    }
    Page {
        item { Panel("A night worth keeping", "One event for your pandal crawl, weekend trip, or party. Photos stay with the people who took them.") {
            OutlinedTextField(title, { title = it.take(60) }, label = { Text("Event name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Button(onClick = { model.createEvent(title); title = "" }, enabled = title.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create event") }
        } }
        item { Panel("Join your friends", "Scan their QR invitation. You will still compare a short code before any private data is exchanged.") {
            Button(onClick = { cameraExplanation = true }, enabled = !busy && !decoding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Scan invitation QR") }
            OutlinedButton(onClick = { qrPicker.launch(arrayOf("image/*")) }, enabled = !busy && !decoding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (decoding) "Reading QR image" else "Choose QR image") }
            OutlinedTextField(invitation, { invitation = it.take(2048) }, label = { Text("Or paste invitation") },
                modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
            OutlinedButton(onClick = { model.joinEvent(invitation) }, enabled = invitation.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Join event") }
        } }
        items(state.events, key = { it.id }) { event ->
            Panel(event.title, if (event.id == selected?.id) "Your selected event" else "Saved on this phone") {
                Button(onClick = { model.selectedEventId.value = event.id; openPhotos() }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open photos") }
                OutlinedButton(onClick = { qrEvent = event }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Show invitation")
                }
            }
        }
    }
    if (cameraExplanation) AlertDialog(onDismissRequest = { cameraExplanation = false }, title = { Text("Read an event QR") },
        text = { Text("The camera is used only to read the invitation. No camera photo is saved or shared. You can also choose a saved QR image.") },
        confirmButton = { TextButton(onClick = { cameraExplanation = false; camera.launch(Manifest.permission.CAMERA) }) { Text("Open camera") } },
        dismissButton = { TextButton(onClick = { cameraExplanation = false }) { Text("Cancel") } })
    qrEvent?.let { event -> InvitationDialog(event, model) { qrEvent = null } }
}

@Composable
private fun InvitationDialog(event: Event, model: AppModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invitation = remember(event) { Invitations.encode(event) }
    val qr = remember(invitation) { invitationQr(invitation) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        uri?.let {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(it)?.use { output ->
                            check(qr.compress(Bitmap.CompressFormat.PNG, 100, output))
                        } ?: error("Destination unavailable")
                    }
                    model.notify("QR invitation saved. Share it only with people you want in this event.")
                } catch (_: Exception) { model.notify("Could not save the invitation. Choose another destination.") }
            }
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(event.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Image(qr.asImageBitmap(), "QR invitation for ${event.title}", Modifier.fillMaxWidth().sizeIn(maxHeight = 280.dp))
            Text("Share with trusted friends. Each phone still has to approve and verify the nearby connection.",
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { export.launch("ThatsMyFace-invitation.png") }) { Text("Save QR image") }
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, invitation)
                }, "Share event invitation"))
            }) { Text("Share invitation") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

internal fun invitationQr(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 720, 720)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

private fun decodeInvitation(resolver: android.content.ContentResolver, uri: Uri): String {
    val bitmap = PhotoDecoder.decode(resolver, uri)
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(luminance)), mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true,
        )).text
    } finally { bitmap.recycle() }
}
