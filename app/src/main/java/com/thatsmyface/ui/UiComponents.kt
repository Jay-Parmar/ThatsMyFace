package com.thatsmyface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thatsmyface.R

@Composable
internal fun SectionHeading(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun FeatureRow(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SearchPhotos(query: String, onQueryChange: (String) -> Unit, label: String = "Search photo names") {
    OutlinedTextField(query, { onQueryChange(it.take(120)) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, label = { Text(label) }, shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("Clear") } })
}

@Composable
internal fun StatusPill(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).background(if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)))
            Text(text, style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PhotoStackArtwork() {
    val lime = MaterialTheme.colorScheme.primary
    val teal = MaterialTheme.colorScheme.secondary
    val dark = MaterialTheme.colorScheme.background
    val muted = MaterialTheme.colorScheme.secondaryContainer
    Canvas(Modifier.fillMaxWidth().height(144.dp)) {
        val width = 160.dp.toPx()
        val height = 108.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        val corner = CornerRadius(15.dp.toPx())
        rotate(-14f, center) {
            drawRoundRect(muted, center - Offset(width / 2 + 18.dp.toPx(), height / 2), Size(width, height), corner)
        }
        rotate(11f, center) {
            drawRoundRect(teal, center - Offset(width / 2 - 15.dp.toPx(), height / 2), Size(width, height), corner)
        }
        drawRoundRect(lime, center - Offset(width / 2, height / 2), Size(width, height), corner)
        listOf(-38f, 0f, 38f).forEachIndexed { index, x ->
            val person = center + Offset(x.dp.toPx(), if (index == 1) -9.dp.toPx() else -2.dp.toPx())
            drawCircle(dark, 10.dp.toPx(), person - Offset(0f, 13.dp.toPx()))
            drawRoundRect(dark, person - Offset(15.dp.toPx(), 0f), Size(30.dp.toPx(), 28.dp.toPx()), CornerRadius(12.dp.toPx()))
        }
        drawLine(dark, center + Offset(-24.dp.toPx(), 40.dp.toPx()), center + Offset(24.dp.toPx(), 40.dp.toPx()), 3.dp.toPx())
    }
}
