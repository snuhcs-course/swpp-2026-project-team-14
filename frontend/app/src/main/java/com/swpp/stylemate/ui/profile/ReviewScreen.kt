package com.swpp.stylemate.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.BodyMeasurement
import com.swpp.stylemate.data.DetectedClothing
import com.swpp.stylemate.data.FakeBodyAnalyzer
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.MAX_STYLES
import com.swpp.stylemate.data.MeasurementType
import com.swpp.stylemate.data.PreferredFit
import com.swpp.stylemate.data.ReferenceMeasurements
import com.swpp.stylemate.data.STYLE_OPTIONS
import com.swpp.stylemate.data.buildInsights
import com.swpp.stylemate.ui.theme.StyleMateTheme
import java.util.Locale

@Composable
fun AnalyzingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text("체형을 분석하고 있어요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "관절 위치와 몸의 윤곽을 찾는 중…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ReviewScreen(
    state: SetupState,
    onRetake: () -> Unit,
    onEdit: (MeasurementType, Double) -> Unit,
    onFit: (PreferredFit) -> Unit,
    onToggleStyle: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    var editing by remember { mutableStateOf<BodyMeasurement?>(null) }

    SetupScaffold(
        title = "AI가 추정한 체형",
        onBack = onRetake,
        actionLabel = "다시 찍기",
        onAction = onRetake,
        primaryLabel = "옷장 등록하고 시작하기  →",
        primaryEnabled = true,
        onPrimary = onConfirm,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "사진으로 추정한 값이에요. 몸의 부위를 누르면 치수를 보고 고칠 수 있어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.warnings.forEach { WarningBanner(it) }
            state.reference?.let { ReferenceNote(it, state.lastInput?.heightCm) }
            Spacer(Modifier.height(16.dp))

            BodyFigureCard(
                measurements = state.measurements,
                gender = state.lastInput?.gender ?: state.gender,
                onEdit = { editing = it },
                reference = state.reference,
            )

            val heightCm = state.lastInput?.heightCm ?: state.heightCm
            if (heightCm != null) {
                InsightSection(buildInsights(state.measurements, heightCm, state.detectedClothing))
            }

            AllMeasurementsSection(state.measurements, onClick = { editing = it }, reference = state.reference)

            SectionTitle("선호 핏")
            FitSelector(selected = state.preferredFit, onSelect = onFit)

            SectionTitle("선호 스타일", "최대 ${MAX_STYLES}개 선택")
            StyleChips(selected = state.preferredStyles, onToggle = onToggleStyle)
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { measurement ->
        EditMeasurementDialog(
            measurement = measurement,
            onDismiss = { editing = null },
            onSave = { value ->
                onEdit(measurement.type, value)
                editing = null
            },
        )
    }
}

@Composable
fun WarningBanner(text: String) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun MeasurementCard(
    items: List<BodyMeasurement>,
    onClick: ((BodyMeasurement) -> Unit)?,
    reference: ReferenceMeasurements? = null,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onClick != null) Modifier.clickable { onClick(item) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.type.label, style = MaterialTheme.typography.bodyLarge)
                    MeasurementStatus(item, reference)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(formatCm(item.valueCm), fontWeight = FontWeight.Bold)
                }
                if (onClick != null) {
                    Spacer(Modifier.size(8.dp))
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "${item.type.label} 수정",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitSelector(selected: PreferredFit, onSelect: (PreferredFit) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PreferredFit.entries.forEachIndexed { index, fit ->
            SegmentedButton(
                selected = fit == selected,
                onClick = { onSelect(fit) },
                shape = SegmentedButtonDefaults.itemShape(index, PreferredFit.entries.size),
            ) {
                Text(fit.label)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleChips(selected: List<String>, onToggle: (String) -> Unit) {
    val full = selected.size >= MAX_STYLES
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STYLE_OPTIONS.forEach { style ->
            val isSelected = style in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(style) },
                enabled = isSelected || !full,
                label = { Text(style) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun EditMeasurementDialog(
    measurement: BodyMeasurement,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(formatNumber(measurement.valueCm)) }
    val value = text.toDoubleOrNull()?.takeIf { it in 5.0..250.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${measurement.type.label} 수정") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter { it.isDigit() || it == '.' }.take(5) },
                label = { Text("cm") },
                singleLine = true,
                isError = value == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.US, value) // "." even where the locale writes ","

fun formatCm(value: Double): String = "${formatNumber(value)} cm"

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun ReviewPreview() {
    val input = AnalysisInput(172, 65, Gender.MALE)
    val clothing = DetectedClothing(topLoose = true)
    val measurements = FakeBodyAnalyzer.MALE_RATIOS
        .filterKeys { it != MeasurementType.UNDERBUST }
        .map { (type, ratio) -> BodyMeasurement(type, ratio * 172, FakeBodyAnalyzer.confidenceFor(type, clothing)) }
    StyleMateTheme {
        ReviewScreen(
            state = SetupState(
                lastInput = input,
                gender = input.gender,
                measurements = measurements,
                detectedClothing = clothing,
                warnings = FakeBodyAnalyzer.warningsFor(input, clothing),
                preferredStyles = listOf("미니멀", "캐주얼"),
            ),
            onRetake = {}, onEdit = { _, _ -> }, onFit = {}, onToggleStyle = {}, onConfirm = {},
        )
    }
}
