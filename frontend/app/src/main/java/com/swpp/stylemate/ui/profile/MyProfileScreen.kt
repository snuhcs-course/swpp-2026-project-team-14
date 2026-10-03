package com.swpp.stylemate.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.BodyMeasurement
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.DetectedClothing
import com.swpp.stylemate.data.Confidence
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.MeasurementType
import com.swpp.stylemate.data.PreferredFit
import com.swpp.stylemate.data.buildInsights
import com.swpp.stylemate.ui.theme.StyleMateTheme

@Composable
fun MyProfileScreen(
    profile: BodyProfile?,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("마이프로필", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (profile == null) {
            EmptyProfile(onReanalyze)
            return@Column
        }

        profile.reference?.let { ReferenceNote(it, profile.input.heightCm) }
        Spacer(Modifier.height(16.dp))
        BodyFigureCard(
            measurements = profile.measurements,
            gender = profile.input.gender,
            onEdit = null,
            reference = profile.reference,
        )

        InsightSection(buildInsights(profile))

        AllMeasurementsSection(profile.measurements, onClick = null, reference = profile.reference)

        SectionTitle("기본 정보")
        InfoCard(profile)

        Spacer(Modifier.height(12.dp))
        Text(
            "※ 사진으로 추정한 스타일링용 참고값이며, 의학적 측정값이 아니에요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onReanalyze, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("체형 다시 분석하기", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmDelete = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text("체형 프로필 삭제")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("체형 프로필을 삭제할까요?") },
            text = { Text("저장된 치수와 선호 정보가 모두 지워져요.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun EmptyProfile(onCreate: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("아직 체형 프로필이 없어요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "사진 두 장으로 내 치수를 알아보세요",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onCreate, shape = RoundedCornerShape(16.dp)) { Text("체형 분석 시작하기") }
        }
    }
}

@Composable
private fun InfoCard(profile: BodyProfile) {
    val rows = listOf(
        "키" to "${profile.input.heightCm} cm",
        "몸무게" to (profile.input.weightKg?.let { "$it kg" } ?: "입력 안 함"),
        "촬영 복장" to "자동 판단 · ${profile.clothing.label}",
        "선호 핏" to profile.preferredFit.label,
        "선호 스타일" to profile.preferredStyles.ifEmpty { listOf("선택 안 함") }.joinToString(", "),
    )
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1800)
@Composable
private fun MyProfilePreview() {
    val profile = BodyProfile(
        input = AnalysisInput(172, 65, Gender.MALE),
        measurements = listOf(
            BodyMeasurement(MeasurementType.SHOULDER_WIDTH, 45.0, Confidence.HIGH),
            BodyMeasurement(MeasurementType.INSEAM, 80.0, Confidence.HIGH),
            BodyMeasurement(MeasurementType.CHEST, 94.0, Confidence.MEDIUM),
            BodyMeasurement(MeasurementType.WAIST, 78.0, Confidence.MEDIUM, editedByUser = true),
            BodyMeasurement(MeasurementType.HIP, 93.0, Confidence.MEDIUM),
        ),
        preferredFit = PreferredFit.LOOSE,
        preferredStyles = listOf("미니멀", "캐주얼"),
        clothing = DetectedClothing(),
    )
    StyleMateTheme { MyProfileScreen(profile, onReanalyze = {}, onDelete = {}) }
}
