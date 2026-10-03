package com.swpp.stylemate.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

/** Common layout for the profile setup steps: title bar on top, one primary button at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScaffold(
    title: String,
    primaryLabel: String?,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
                actions = {
                    if (actionLabel != null) {
                        TextButton(onClick = onAction) {
                            Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (primaryLabel != null) {
                Box(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Button(
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Text(primaryLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (hint != null) {
            Spacer(Modifier.size(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
