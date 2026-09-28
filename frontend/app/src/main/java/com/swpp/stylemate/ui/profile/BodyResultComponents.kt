package com.swpp.stylemate.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.BodyMeasurement
import com.swpp.stylemate.data.Insight
import com.swpp.stylemate.data.MeasurementGroup

/** "체형 인사이트" cards, shown right below the body figure. */
@Composable
fun InsightSection(insights: List<Insight>) {
    if (insights.isEmpty()) return
    SectionTitle("체형 인사이트")
    insights.forEach { InsightCard(it) }
}

@Composable
private fun InsightCard(insight: Insight) {
    val isNote = insight.title == "안내"
    Column(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isNote) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            insight.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isNote) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(insight.body, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Every measurement grouped by kind, collapsed by default (the figure already shows them). */
@Composable
fun AllMeasurementsSection(measurements: List<BodyMeasurement>, onClick: ((BodyMeasurement) -> Unit)?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
    ) {
        Text(
            "전체 치수 보기 (${measurements.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "접기" else "펼치기",
        )
    }
    AnimatedVisibility(expanded) {
        Column {
            MeasurementGroup.entries.forEach { group ->
                val items = measurements.filter { it.type.group == group }
                if (items.isNotEmpty()) {
                    SectionTitle(group.label, "cm")
                    MeasurementCard(items = items, onClick = onClick)
                }
            }
        }
    }
}
