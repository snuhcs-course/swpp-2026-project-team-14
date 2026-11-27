// MY account overview and explicit preference editing.
package com.swpp.stylemate.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.stylemate.data.*
import com.swpp.stylemate.ui.components.PageHeader
import com.swpp.stylemate.ui.components.ScreenScaffold
import com.swpp.stylemate.ui.theme.StyleMateTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

    ScreenScaffold("내 체형 정보", null, false, {}, modifier,
        onBack = onBack, navigationEnabled = !deleting, contentWindowInsets = WindowInsets(0)) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        if (profile == null) {
            EmptyProfile(onReanalyze)
            return@Column
        }

        InfoCard(profile)
        Spacer(Modifier.height(16.dp))
        BodySummaryFigure(profile)
        profile.reference?.let { ReferenceNote(it, profile.input.heightCm) }

        InsightSection(buildInsights(profile))

        AllMeasurementsSection(profile.measurements, onClick = null, reference = profile.reference)

        Spacer(Modifier.height(12.dp))
        Text(
            "※ 사진으로 추정한 스타일링용 참고값이며, 의학적 측정값이 아니에요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onReanalyze, enabled = !deleting, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("체형 다시 분석하기", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { deleteError = null; confirmDelete = true }, enabled = !deleting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("체형 프로필 삭제")
        }
        Spacer(Modifier.height(24.dp))
    }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("체형 프로필을 삭제할까요?") },
            text = { Text(deleteError ?: "저장된 체형과 치수가 지워져요. 선호 핏·무드·컬러는 유지돼요.") },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    if (onDeleteRemote == null) { confirmDelete = false; onDelete(); onBack() }
                    else {
                        deleting = true
                        deleteError = null
                        scope.launch {
                            try { deleteError = onDeleteRemote(); if (deleteError == null) { confirmDelete = false; onBack() } }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { deleteError = error.message ?: "삭제하지 못했어요. 다시 시도해 주세요." }
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
            Button(onClick = onCreate, shape = MaterialTheme.shapes.medium) { Text("체형 분석 시작하기") }
        }
    }
}

@Composable
private fun InfoCard(profile: BodyProfile) {
    val rows = listOf(
        "키" to "${profile.input.heightCm} cm",
        "몸무게" to (profile.input.weightKg?.let { "$it kg" } ?: "입력 안 함"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.forEach { (label, value) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("촬영 복장: ${profile.clothing.label} · 자동 판단", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private enum class ProfilePage { MAIN, BODY, CONNECTIONS, ACCOUNT }

@Composable
fun MyProfileScreen(
    profile: BodyProfile?,
    style: StyleProfile,
    onSaveStyle: suspend (StyleProfile) -> String?,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    username: String? = null,
    onLogout: () -> Unit = {},
    authBusy: Boolean = false,
    onDeleteRemote: (suspend () -> String?)? = null,
    connections: (@Composable () -> Unit)? = null,
    connectionSummary: String = "연결 상태 확인",
) {
    var page by rememberSaveable { mutableStateOf(ProfilePage.MAIN) }
    val pages = rememberSaveableStateHolder()
    val editor: ProfilePreferencesViewModel = if (LocalInspectionMode.current) remember { ProfilePreferencesViewModel() }
        else viewModel(factory = viewModelFactory { initializer { ProfilePreferencesViewModel() } })
    val editing by editor.state.collectAsState()
    BackHandler(enabled = page != ProfilePage.MAIN && editing.section == null) { page = ProfilePage.MAIN }
    if (editing.section != null) {
        ProfilePreferenceEditor(editing, modifier, editor::select, editor::discard,
            onFit = { if (editing.section == ProfilePreference.TASTE) editor.selectFit(it) else editor.select(it) },
            onMood = { if (editing.section == ProfilePreference.TASTE) editor.toggleMood(it) else editor.select(it) }) {
            if (editing.section == ProfilePreference.TASTE) editor.saveTaste { fits, moods ->
                onSaveStyle(style.copy(preferredFits = fits, preferredStyles = moods))
            } else editor.save { section, values ->
                onSaveStyle(when (section) {
                    ProfilePreference.FIT -> style.copy(preferredFits = values)
                    ProfilePreference.MOOD -> style.copy(preferredStyles = values)
                    ProfilePreference.COLORS -> style.copy(favoriteColors = values)
                    ProfilePreference.TASTE -> style
                })
            }
        }
        return
    }
    pages.SaveableStateProvider(page.name) {
        when (page) {
            ProfilePage.BODY -> BodyDetailsScreen(profile, onReanalyze, onDelete,
                { page = ProfilePage.MAIN }, modifier, onDeleteRemote)
            ProfilePage.CONNECTIONS -> ScreenScaffold("캘린더 연동", null, false, {}, modifier,
                onBack = { page = ProfilePage.MAIN }, contentWindowInsets = WindowInsets(0)) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    connections?.invoke()
                }
            }
            ProfilePage.ACCOUNT -> AccountSettingsScreen(username, authBusy, onLogout, { page = ProfilePage.MAIN }, modifier)
            ProfilePage.MAIN -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PageHeader("MY", subtitle = "내 체형과 취향", horizontalPadding = 0.dp)
                editing.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                ProfileSummaryRow("내 체형 정보", profile?.let {
                    listOfNotNull("키 ${it.input.heightCm} cm", it.input.weightKg?.let { kg -> "몸무게 $kg kg" }).joinToString(" · ")
                } ?: "아직 등록된 체형 정보가 없어요") { page = ProfilePage.BODY }
                ProfileHeading("스타일")
                ProfileSummaryRow("나의 취향", listOf(FIT_LABELS[style.preferredFits.firstOrNull()] ?: "레귤러",
                    style.preferredStyles.joinToString(" · ") { MOOD_LABELS[it] ?: it }.ifBlank { "선택한 무드 없음" }).joinToString(" · "),
                    enabled = !authBusy) { editor.open(ProfilePreference.TASTE, style) }
                ProfileSummaryRow("컬러 기록", style.favoriteColors.joinToString(" · ") { PROFILE_COLORS[it] ?: it }
                    .ifBlank { "기록한 컬러가 없어요" }, enabled = !authBusy) { editor.open(ProfilePreference.COLORS, style) }
                if (connections != null) { ProfileHeading("캘린더 연동"); connections() }
                ProfileHeading("계정")
                ProfileSummaryRow("계정 설정", "로그인 정보 · 로그아웃", enabled = !authBusy) { page = ProfilePage.ACCOUNT }
            }
        }
    }
}

@Composable
private fun ProfileHeading(title: String) {
    Text(title, Modifier.padding(top = 12.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ProfileSummaryRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AccountSettingsScreen(username: String?, busy: Boolean, onLogout: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold("계정 설정", null, false, {}, modifier, onBack = onBack, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("로그인 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("아이디", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(username.orEmpty(), Modifier.weight(2f))
            }
            HorizontalDivider()
            TextButton(onClick = { confirm = true }, enabled = !busy,
                contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (busy) "로그아웃 중…" else "로그아웃", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!busy) confirm = false }, title = { Text("로그아웃할까요?") },
        text = { Text("옷장과 저장한 코디는 계정에 남아 있어요.") },
        confirmButton = { TextButton(onClick = { confirm = false; onLogout() }, enabled = !busy) { Text("로그아웃") } },
        dismissButton = { TextButton(onClick = { confirm = false }, enabled = !busy) { Text("취소") } })
}
