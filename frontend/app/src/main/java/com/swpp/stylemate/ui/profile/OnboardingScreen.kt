// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swpp.stylemate.R
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.ui.components.ScreenScaffold
import com.swpp.stylemate.ui.wardrobe.WardrobeRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val ONBOARDING_FITS = linkedMapOf("oversized" to "오버핏", "wide" to "와이드", "regular" to "레귤러", "tapered" to "테이퍼드", "slim" to "슬림")
private val ONBOARDING_MOODS = linkedMapOf("minimal" to "미니멀", "cityboy" to "시티보이", "casual" to "캐주얼", "street" to "스트릿", "classic" to "클래식 / 프레피", "sporty" to "스포티")
private val MOOD_IMAGES = mapOf("minimal" to R.drawable.onboarding_minimal, "cityboy" to R.drawable.onboarding_cityboy,
    "casual" to R.drawable.onboarding_casual, "street" to R.drawable.onboarding_street,
    "classic" to R.drawable.onboarding_classic, "sporty" to R.drawable.onboarding_sporty)
// Key pieces of each mood, so the card says what the word means without relying on the photo alone (HE).
private val MOOD_HINTS = mapOf("minimal" to "무채색 · 깔끔한 실루엣", "cityboy" to "셔츠 · 와이드 치노 · 볼캡",
    "casual" to "티셔츠 · 데님 · 스니커즈", "street" to "후드 · 카고 팬츠", "classic" to "블레이저 · 슬랙스 · 로퍼",
    "sporty" to "바람막이 · 트레이닝 팬츠")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingHeader(step: Int, onBack: () -> Unit, onSkip: () -> Unit, enabled: Boolean = true) {
    Column {
        TopAppBar(title = { Text("StyleMate", style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onBack, enabled = enabled && step > 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            } },
            actions = { TextButton(onClick = onSkip, enabled = enabled) { Text("건너뛰기", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("STEP ${step.toString().padStart(2, '0')} / 08", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { step / 8f }, modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {})
        }
    }
}

@Composable
fun OnboardingRoute(model: BodyProfileViewModel, accountStore: LocalUserStore? = null) {
    val context = LocalContext.current
    val account = checkNotNull(com.swpp.stylemate.ui.LocalAccountAccess.current)
    val store = remember(accountStore, account.userId) { accountStore ?: LocalUserStore.forContext(context, account.userId) }
    val progress by model.onboarding.collectAsStateWithLifecycle()
    val app by model.app.collectAsStateWithLifecycle()
    val body by model.setup.collectAsStateWithLifecycle()
    var records by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var checkingExistingAccount by remember { mutableStateOf(progress.step == 1) }
    LaunchedEffect(progress.step, refresh) {
        if (progress.step in setOf(1, 6, 8)) {
            try {
                val rows = WardrobeRepository.json("/api/wardrobe/items/", store = store, account = account).getJSONArray("items")
                records = (0 until rows.length()).map { rows.getJSONObject(it) }; loadError = null
                if (progress.step == 1 && records.isNotEmpty()) model.skipSetup()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loadError = "옷장을 불러오지 못했어요." }
            finally { if (progress.step == 1) checkingExistingAccount = false }
        }
    }
    if (progress.step == 1 && (checkingExistingAccount || loadError != null)) {
        ScreenScaffold("StyleMate", null, false, {}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (checkingExistingAccount) { CircularProgressIndicator(); Text("계정 정보를 확인하고 있어요.") }
                else { Text(loadError.orEmpty()); TextButton(onClick = { checkingExistingAccount = true; refresh++ }) { Text("다시 확인") } }
            }
        }
        return
    }
    val step = progress.step
    val chosenFits = remember(progress.draft) {
        val values = JSONObject(progress.draft).optJSONArray("preferred_fits")
        (0 until (values?.length() ?: 0)).map { values!!.getString(it) }
    }
    val busy = body.step == SetupStep.ANALYZING || body.saving
    val back = {
        when (step) {
            5 -> model.moveOnboarding(4)
            6 -> model.moveOnboarding(if (body.step == SetupStep.REVIEW && body.lastInput != null) 5 else 4)
            7 -> model.finishOnboardingGarment()
            8 -> model.moveOnboarding(6)
            else -> model.moveOnboarding((step - 1).coerceAtLeast(1))
        }
    }
    val skip = {
        when (step) {
            1, 8 -> model.skipSetup()
            2 -> model.confirmOnboardingFits()
            3 -> model.confirmOnboardingStyles()
            4, 5 -> model.skipOnboardingBody()
            6 -> model.moveOnboarding(8)
            else -> model.finishOnboardingGarment()
        }
    }
    BackHandler(enabled = step > 1 && !busy, onBack = back)
    BackHandler(enabled = busy) {}
    val header: @Composable () -> Unit = { OnboardingHeader(step, back, skip, !busy) }
    when (step) {
        1 -> OnboardingWelcome(header, { model.moveOnboarding(2) })
        4 -> if (body.step == SetupStep.ANALYZING) Column(Modifier.fillMaxSize()) {
            header(); Box(Modifier.weight(1f)) { AnalyzingScreen() }
        } else PhotoInputScreen(body, back, model::setPhoto, model::showError, model::setHeight,
            model::setWeight, model::setGender, model::analyze, topBar = header)
        5 -> ReviewScreen(body, { model.moveOnboarding(4) }, model::editMeasurement,
            model::setPreferredFit, model::toggleStyle, model::confirmProfile, topBar = header, onboarding = true)
        7 -> WardrobeRoute(onboardingDraft = JSONObject(progress.draft).optJSONObject("garment"),
            localStore = store,
            onDraftChange = model::saveOnboardingGarment, onSaved = model::finishOnboardingGarment,
            onCancel = model::finishOnboardingGarment,
            onboardingTopBar = { routeBack, enabled -> OnboardingHeader(7, routeBack, routeBack, enabled && !busy) })
        else -> ScreenScaffold(title = "StyleMate", primaryLabel = when (step) {
            8 -> "홈으로 가기"
            else -> "다음"
        }, primaryEnabled = !busy && (loadError == null || step !in setOf(6, 8)), onPrimary = {
            when (step) { 2 -> model.confirmOnboardingFits()
                3 -> model.confirmOnboardingStyles(); 6 -> model.moveOnboarding(8); 8 -> model.skipSetup() }
        }, topBar = header) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (step) {
                    2 -> {
                        OnboardingTitle("어떤 핏을 좋아하세요?")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("minimal", "cityboy", "classic").forEach { mood ->
                                Image(painterResource(MOOD_IMAGES.getValue(mood)), contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).height(144.dp).clip(MaterialTheme.shapes.medium))
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ONBOARDING_FITS.forEach { (code, label) ->
                            val selected = code in chosenFits
                            Surface(selected = selected, onClick = { model.setOnboardingFits(
                                if (selected) chosenFits - code else chosenFits + code) },
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    SelectionMark(selected)
                                }
                            }
                        } }
                        body.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    3 -> {
                        OnboardingTitle("어떤 분위기를 좋아하세요?")
                        ONBOARDING_MOODS.entries.toList().chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { (code, label) ->
                                    val selected = code in app.style.preferredStyles
                                    Card(enabled = !busy, onClick = { model.updateOnboardingStyle(app.style.copy(preferredStyles =
                                        if (selected) app.style.preferredStyles - code else app.style.preferredStyles + code)) },
                                        modifier = Modifier.weight(1f).semantics { this.selected = selected }, shape = MaterialTheme.shapes.medium,
                                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                        Box {
                                            Image(painterResource(MOOD_IMAGES.getValue(code)), contentDescription = null, contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxWidth().height(180.dp))
                                            SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(10.dp))
                                        }
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            MOOD_HINTS[code]?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                    }
                                }
                            }
                        }
                        body.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    6 -> {
                        OnboardingTitle("자주 입는 옷부터 담아볼까요?")
                        Text("상의와 하의를 2벌씩 등록하면 코디를 비교하기 좋아요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OnboardingCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                listOf(Triple("top", "상의", 2), Triple("bottom", "하의", 2), Triple("outerwear", "아우터", 1)).forEachIndexed { index, (category, label, target) ->
                                    if (index > 0) VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    Text("$label ${records.count { it.getJSONObject("attributes").optString("category") == category }}/$target",
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1)
                                }
                            }
                        }
                        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("다시 불러오기") } }
                        for ((category, label) in listOf("top" to "상의 추가", "bottom" to "하의 추가", "outerwear" to "아우터 추가")) {
                            OutlinedButton(onClick = { model.startOnboardingGarment(category) }, modifier = Modifier.fillMaxWidth()) { Text("+ $label") }
                        }
                        GarmentSummary(records, store) { row -> model.startOnboardingGarment(row.getJSONObject("attributes").getString("category"), row.getString("id")) }
                    }
                    8 -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Check, null, Modifier.size(32.dp))
                            OnboardingTitle("이제 내 옷으로 시작해요")
                            Text("취향은 MY에서, 등록한 옷은 옷장에서 바꿀 수 있어요.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OnboardingCard {
                            Text("선택한 취향", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(app.style.preferredFits.joinToString(" · ") { ONBOARDING_FITS[it] ?: it })
                            Text(app.style.preferredStyles.joinToString(" · ") { ONBOARDING_MOODS[it] ?: it }.ifBlank { "분위기는 나중에 골라도 괜찮아요." },
                                style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider()
                            Text("등록한 옷 ${records.size}벌", style = MaterialTheme.typography.bodyMedium)
                            Text(if (app.profile != null) "체형 정보 저장 완료" else "체형 분석은 나중에 이어서 할 수 있어요.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("다시 불러오기") } }
                        if (records.none { it.getJSONObject("attributes").optString("category") == "top" } ||
                            records.none { it.getJSONObject("attributes").optString("category") == "bottom" }) {
                            Text("상의와 하의를 등록하면 바로 코디를 추천받을 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                        }

                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun OnboardingTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.size(24.dp), shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun OnboardingWelcome(header: @Composable () -> Unit, onStart: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = header, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                Text("시작하기", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }

        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.drawable.onboarding_welcome), contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.25f).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
            Text("내 옷으로, 더 나은 오늘을.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("내 취향과 옷장을 담아 나만의 스타일을 시작해요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnboardingCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun GarmentSummary(records: List<JSONObject>, store: LocalUserStore, onClick: ((JSONObject) -> Unit)? = null) {
    val context = LocalContext.current
    val account = checkNotNull(com.swpp.stylemate.ui.LocalAccountAccess.current)
    records.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { record ->
                val photo by produceState<ImageBitmap?>(null, record.getString("id")) {
                    try { value = WardrobeRepository.thumbnail(context, record.getString("image_url"), account = account) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { value = null }
                }
                Surface(onClick = { onClick?.invoke(record) }, enabled = onClick != null, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f)) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            photo?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text("사진", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(record.getJSONObject("attributes").optString("name"), Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
