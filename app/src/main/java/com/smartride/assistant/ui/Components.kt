package com.smartride.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.RideAnalysis
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF0F766E)

@Composable
fun SmartRideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White,
            secondary = Color(0xFF334155), background = Color(0xFFF6F8F8), surface = Color.White,
        ),
        content = content,
    )
}

fun classColor(c: Classification): Color = when (c) {
    Classification.FAVOURABLE -> Color(0xFF15803D)
    Classification.MODERATE -> Color(0xFFB45309)
    Classification.NOT_SUITABLE -> Color(0xFFB91C1C)
    Classification.NEEDS_REVIEW -> Color(0xFF475569)
}

fun fmtKm(v: Double?): String = if (v == null) "-" else String.format(Locale.US, "%.1f km", v)
fun fmtTime(ms: Long?): String =
    if (ms == null) "-" else SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(ms))

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp), maxLines = 3)
    }
}

@Composable
fun ClassBadge(c: Classification) {
    Text(
        c.label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(classColor(c), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** One analysed ride. [onEdit] is offered when OCR needs manual correction. */
@Composable
fun RideCard(a: RideAnalysis, onEdit: (() -> Unit)? = null) {
    val o = a.offer
    SectionCard(title = "Rs. ${o.fare ?: "?"}") {
        ClassBadge(a.classification)
        LabelValue("Pickup", o.pickup ?: "not read")
        LabelValue("Destination", o.destination ?: "not read")
        LabelValue("Pickup distance", fmtKm(o.pickupDistanceKm) + (o.pickupEtaMin?.let { " ($it min)" } ?: ""))
        LabelValue("Trip distance", fmtKm(o.tripDistanceKm) + (o.tripDurationMin?.let { " ($it min)" } ?: ""))
        LabelValue("Additional commute distance", fmtKm(a.additionalKm?.coerceAtLeast(0.0)))
        LabelValue("Route fit index", a.fitIndex?.let { "$it / 100 (heuristic)" } ?: "-")
        LabelValue("OCR confidence", "${(o.ocrConfidence * 100).toInt()}%" + if (o.corrected) " (corrected by you)" else "")
        Text(a.reason, style = MaterialTheme.typography.bodyMedium)
        if (onEdit != null && a.classification == Classification.NEEDS_REVIEW) {
            OutlinedButton(onClick = onEdit) { Text("Correct text and re-check") }
        }
    }
}
