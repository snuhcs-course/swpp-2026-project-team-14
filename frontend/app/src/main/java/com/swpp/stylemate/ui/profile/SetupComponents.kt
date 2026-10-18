// AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
package com.swpp.stylemate.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.Confidence
import com.swpp.stylemate.data.formatPercent
import com.swpp.stylemate.data.ReferenceMeasurements
import com.swpp.stylemate.data.BodyMeasurement
import com.swpp.stylemate.ui.theme.ConfidenceHigh
import com.swpp.stylemate.ui.theme.ConfidenceLow
import com.swpp.stylemate.ui.theme.ConfidenceMedium

/**
 * What is shown under a measurement: real accuracy for our benchmark photos, otherwise the
 * confidence badge (Iteration 1 only, see docs/body-analysis/02-design.md), plus "직접 수정함".
 */
@Composable
fun MeasurementStatus(measurement: BodyMeasurement, reference: ReferenceMeasurements?) {
    val accuracy = reference?.accuracyPercent(measurement)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (measurement.editedByUser) {
            Text("직접 수정함", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (accuracy != null) Spacer(Modifier.size(8.dp))
        }
        when {
            accuracy != null -> AccuracyLabel(accuracy)
            !measurement.editedByUser -> ConfidenceBadge(measurement.confidence)
        }
    }
}

@Composable
private fun AccuracyLabel(percent: Double) {
    val color = when {
        percent >= 97.0 -> ConfidenceHigh
        percent >= 93.0 -> ConfidenceMedium
        else -> ConfidenceLow
    }
    Text("정확도 ${formatPercent(percent)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
}

@Composable
fun ConfidenceBadge(confidence: Confidence) {
    val color = when (confidence) {
        Confidence.HIGH -> ConfidenceHigh
        Confidence.MEDIUM -> ConfidenceMedium
        Confidence.LOW -> ConfidenceLow
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(4.dp))
        Text(confidence.label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
