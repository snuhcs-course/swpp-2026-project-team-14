// AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
package com.swpp.stylemate.ui.profile

import com.swpp.stylemate.ui.components.SectionTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import com.swpp.stylemate.data.StyleProfile
import com.swpp.stylemate.ui.components.ScreenScaffold

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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
private fun BodyDetailsScreen(
    profile: BodyProfile?,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteRemote: (suspend () -> String?)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = deleting) {}

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack, enabled = !deleting) { Text("뒤로") }
        Text("내 체형 정보", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

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
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("체형 프로필을 삭제할까요?") },
            text = { Text(deleteError ?: "저장된 체형과 치수가 지워져요. 선호 핏·무드·컬러는 유지돼요.") },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    if (onDeleteRemote == null) { confirmDelete = false; onDelete() }
                    else {
                        deleting = true
                        scope.launch {
                            try { deleteError = onDeleteRemote(); if (deleteError == null) confirmDelete = false }
                            finally { deleting = false }
                        }
                    }
                }) { Text(if (deleting) "삭제 중…" else "삭제") }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("취소") } },
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
    StyleMateTheme { MyProfileScreen(profile, StyleProfile(), onSaveStyle = { null }, onReanalyze = {}, onDelete = {}) }
}

private enum class ProfilePage { MAIN, COLORS, BODY }
private val FIT_LABELS = linkedMapOf("oversized" to "오버핏", "wide" to "와이드", "regular" to "레귤러", "tapered" to "테이퍼드", "slim" to "슬림")
private val MOOD_LABELS = linkedMapOf("minimal" to "미니멀", "cityboy" to "시티보이", "casual" to "캐주얼", "street" to "스트릿", "classic" to "클래식 / 프레피", "sporty" to "스포티")
private val PROFILE_COLORS = linkedMapOf("#202020" to "블랙", "#FFFFFF" to "화이트", "#888888" to "그레이", "#1F2A44" to "네이비",
    "#C7B69A" to "베이지", "#F0E6D2" to "크림", "#795548" to "브라운", "#686B42" to "카키",
    "#3975C6" to "블루", "#E9A2B3" to "핑크", "#B83232" to "레드", "#9464AA" to "퍼플")
private val PROFILE_IMPORT_ICON = ImageVector.Builder(name = "ImportData", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 9f); lineTo(15f, 9f); lineTo(15f, 3f); lineTo(9f, 3f)
        lineTo(9f, 9f); lineTo(5f, 9f); lineTo(12f, 16f); close()
        moveTo(5f, 18f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 18f); close()
    }
}.build()

@Composable
fun MyProfileScreen(
    profile: BodyProfile?,
    style: StyleProfile,
    onSaveStyle: suspend (StyleProfile) -> String?,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onLogin: (() -> Unit)? = null,
    username: String? = null,
    onLogout: () -> Unit = {},
    authBusy: Boolean = false,
    onDeleteRemote: (suspend () -> String?)? = null,
    onImport: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableStateOf(ProfilePage.MAIN) }
    var inlineSaving by remember { mutableStateOf(false) }
    var pendingStyle by remember { mutableStateOf<StyleProfile?>(null) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val shownStyle = pendingStyle ?: style
    val saveInline: (StyleProfile) -> Unit = { value ->
        if (!inlineSaving && !authBusy) {
            inlineSaving = true
            inlineError = null
            pendingStyle = value
            scope.launch {
                try { inlineError = onSaveStyle(value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { inlineError = failure.message ?: "저장하지 못했어요. 다시 선택해 주세요." }
                finally { pendingStyle = null; inlineSaving = false }
            }
        }
    }
    val back = { page = ProfilePage.MAIN }
    BackHandler(enabled = page != ProfilePage.MAIN, onBack = back)
    when (page) {
        ProfilePage.BODY -> BodyDetailsScreen(profile, onReanalyze, onDelete, back, modifier, onDeleteRemote)
        ProfilePage.COLORS -> ColorPreferenceEditor(style.favoriteColors, modifier, back) { onSaveStyle(style.copy(favoriteColors = it)) }
        ProfilePage.MAIN -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("마이프로필", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (username != null) onImport?.let {
                    IconButton(onClick = it, enabled = !authBusy && !inlineSaving) {
                        Icon(PROFILE_IMPORT_ICON, contentDescription = "이 기기의 데이터 가져오기", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (username == null) {
                        Text("내 정보를 계정에 보관하세요", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (onLogin != null) Button(onClick = onLogin, enabled = !inlineSaving, shape = RoundedCornerShape(12.dp)) { Text("로그인") }
                    } else {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(username, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("로그인됨", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onLogout, enabled = !authBusy && !inlineSaving) { Text(if (authBusy) "처리 중…" else "로그아웃") }
                    }
                }
            }
            if (username == null) Text("비로그인 데이터는 이 기기에만 저장돼요. 앱 삭제 또는 데이터 초기화 시 사라질 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inlineError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ProfileCard {
                Text("선호하는 핏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PreferenceChoices(FIT_LABELS, shownStyle.preferredFits, !inlineSaving && !authBusy) {
                    saveInline(style.copy(preferredFits = it.ifEmpty { listOf("regular") }))
                }
            }
            ProfileCard {
                Text("선호하는 무드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PreferenceChoices(MOOD_LABELS, shownStyle.preferredStyles, !inlineSaving && !authBusy) {
                    saveInline(style.copy(preferredStyles = it))
                }
            }
            ProfileCard {
                ProfileSectionHeader("자주 입는 컬러", "팔레트 관리", !inlineSaving && !authBusy) { page = ProfilePage.COLORS }
                if (style.favoriteColors.isEmpty()) {
                    Text("아직 저장한 컬러가 없어요", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else ColorTags(style.favoriteColors)
            }
            SectionTitle("체형 밸런스 참고")
            Surface(onClick = { page = ProfilePage.BODY }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (profile == null) "내 체형 분석하기" else "내 체형 정보 보기", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProfileCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ProfileSectionHeader(title: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, enabled = enabled) { Text(action, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceChoices(options: Map<String, String>, selected: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (code, label) ->
            FilterChip(selected = code in selected, enabled = enabled,
                onClick = { onChange(if (code in selected) selected - code else selected + code) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorTags(colors: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEach { hex -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(24.dp), shape = CircleShape, color = Color(android.graphics.Color.parseColor(hex)), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
            Text(PROFILE_COLORS[hex] ?: hex, style = MaterialTheme.typography.bodyMedium)
        } }
    }
}

@Composable
private fun ColorPreferenceEditor(initial: List<String>,
    modifier: Modifier, onBack: () -> Unit, onSave: suspend (List<String>) -> String?) {
    val options = PROFILE_COLORS + initial.associateWith { PROFILE_COLORS[it] ?: it }
    var selected by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = saving) {}
    ScreenScaffold("자주 입는 컬러", if (saving) "저장 중…" else "저장하기", !saving, {
        if (!saving) {
            saving = true
            scope.launch {
                try { error = onSave(selected); if (error == null) onBack() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "저장하지 못했어요. 다시 시도해 주세요." }
                finally { saving = false }
            }
        }
    }, modifier = modifier, onBack = onBack, navigationEnabled = !saving, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ProfileCard {
                Text("선택한 컬러 ${selected.size}/8", fontWeight = FontWeight.Bold)
                if (selected.isEmpty()) Text("선택한 컬러가 없어요", color = MaterialTheme.colorScheme.onSurfaceVariant) else ColorTags(selected)
            }
            ProfileCard {
                Text("컬러 선택", fontWeight = FontWeight.Bold)
                ColorChoices(options, selected, 8, !saving) { selected = it; error = null }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ColorChoices(options: Map<String, String>, selected: List<String>, limit: Int, enabled: Boolean, onChange: (List<String>) -> Unit) {
    options.entries.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (hex, label) ->
                val checked = hex in selected
                Surface(onClick = { onChange(if (checked) selected - hex else selected + hex) },
                    enabled = enabled && (checked || selected.size < limit), selected = checked,
                    shape = RoundedCornerShape(12.dp), color = Color.Transparent, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(Modifier.size(48.dp), shape = CircleShape, color = Color(android.graphics.Color.parseColor(hex)),
                            border = BorderStroke(if (checked) 2.dp else 1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Box(contentAlignment = Alignment.Center) {
                                if (checked) Icon(Icons.Filled.Check, contentDescription = null,
                                    tint = if (hex in setOf("#202020", "#1F2A44", "#795548", "#686B42", "#3975C6", "#B83232", "#9464AA")) Color.White else Color.Black)
                            }
                        }
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
