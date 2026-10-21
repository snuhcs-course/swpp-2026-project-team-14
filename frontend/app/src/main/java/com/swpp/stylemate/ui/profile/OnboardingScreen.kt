// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
            LinearProgressIndicator(progress = { step / 8f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun OnboardingRoute(model: BodyProfileViewModel, onLogin: () -> Unit, guestStore: LocalUserStore? = null) {
    val context = LocalContext.current
    val store = remember(guestStore) { guestStore ?: LocalUserStore.forContext(context) }
    val progress by model.onboarding.collectAsStateWithLifecycle()
    val app by model.app.collectAsStateWithLifecycle()
    val body by model.setup.collectAsStateWithLifecycle()
    var records by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(progress.step, refresh) {
        if (progress.step in setOf(6, 8)) {
            try { records = withContext(Dispatchers.IO) { store.garments() }; loadError = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loadError = "옷장을 불러오지 못했어요." }
        }
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
            3 -> model.moveOnboarding(4)
            4, 5 -> model.skipOnboardingBody()
            6 -> model.moveOnboarding(8)
            else -> model.finishOnboardingGarment()
        }
    }
    BackHandler(enabled = step > 1 && !busy, onBack = back)
    BackHandler(enabled = busy) {}
    val header: @Composable () -> Unit = { OnboardingHeader(step, back, skip, !busy) }
    when (step) {
        1 -> OnboardingWelcome(header, { model.moveOnboarding(2) }, onLogin)
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
            onboardingTopBar = { routeBack, enabled -> OnboardingHeader(7, routeBack, routeBack, enabled) })
        else -> ScreenScaffold(title = "StyleMate", primaryLabel = when (step) {
            8 -> "첫 코디 보러 가기"
            else -> "다음"
        }, primaryEnabled = loadError == null || step !in setOf(6, 8), onPrimary = {
            when (step) { 2 -> model.confirmOnboardingFits()
                3 -> model.moveOnboarding(4); 6 -> model.moveOnboarding(8); 8 -> model.skipSetup() }
        }, topBar = header) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (step) {
                    2 -> {
                        OnboardingTitle("어떤 핏을 좋아하세요?")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("minimal", "cityboy", "classic").forEach { mood ->
                                Image(painterResource(MOOD_IMAGES.getValue(mood)), contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).height(144.dp).clip(RoundedCornerShape(12.dp)))
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ONBOARDING_FITS.forEach { (code, label) ->
                            val selected = code in chosenFits
                            Surface(selected = selected, onClick = { model.setOnboardingFits(
                                if (selected) chosenFits - code else chosenFits + code) },
                                shape = RoundedCornerShape(12.dp),
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
                                    Card(onClick = { model.updateOnboardingStyle(app.style.copy(preferredStyles =
                                        if (selected) app.style.preferredStyles - code else app.style.preferredStyles + code)) },
                                        modifier = Modifier.weight(1f).semantics { this.selected = selected }, shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                        Box {
                                            Image(painterResource(MOOD_IMAGES.getValue(code)), contentDescription = null, contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxWidth().height(180.dp))
                                            SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(10.dp))
                                        }
                                        Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                        body.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    6 -> {
                        OnboardingTitle("자주 입는 옷부터 담아볼까요?")
                        Text("상의 2벌 · 하의 2벌 · 신발 1켤레를 권장해요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OnboardingCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                listOf(Triple("top", "상의", 2), Triple("bottom", "하의", 2), Triple("shoes", "신발", 1)).forEachIndexed { index, (category, label, target) ->
                                    if (index > 0) VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    Text("$label ${records.count { it.getJSONObject("attributes").optString("category") == category }}/$target",
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1)
                                }
                            }
                        }
                        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("다시 불러오기") } }
                        for ((category, label) in listOf("top" to "상의 추가", "bottom" to "하의 추가", "shoes" to "신발 추가")) {
                            OutlinedButton(onClick = { model.startOnboardingGarment(category) }, modifier = Modifier.fillMaxWidth()) { Text("+ $label") }
                        }
                        GarmentSummary(records, store) { row -> model.startOnboardingGarment(row.getJSONObject("attributes").getString("category"), row.getString("id")) }
                    }
                    8 -> {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50), modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally)) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        OnboardingTitle("시작할 준비가 됐어요!")
                        OnboardingCard {
                            Text("나의 스타일", fontWeight = FontWeight.Bold)
                            Text(app.style.preferredFits.joinToString(" · ") { ONBOARDING_FITS[it] ?: it })
                            if (app.style.preferredStyles.isNotEmpty()) Text(app.style.preferredStyles.joinToString(" · ") { ONBOARDING_MOODS[it] ?: it })
                        }
                        Text("등록한 옷 ${records.size}개", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("다시 불러오기") } }
                        GarmentSummary(records, store)
                        Text("입력한 정보는 이 기기에 저장됐어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { model.skipSetup(); if (model.onboarding.value.completed) onLogin() }, modifier = Modifier.fillMaxWidth()) { Text("로그인 / 회원가입") }
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
private fun OnboardingWelcome(header: @Composable () -> Unit, onStart: () -> Unit, onLogin: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = header, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("시작하기", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onLogin) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("이미 계정이 있나요? ") }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) { append("로그인") }
                }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.drawable.onboarding_welcome), contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Text("내 옷으로, 더 나은 오늘을.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("내 취향과 옷장을 담아 나만의 스타일을 시작해요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnboardingCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun GarmentSummary(records: List<JSONObject>, store: LocalUserStore, onClick: ((JSONObject) -> Unit)? = null) {
    val context = LocalContext.current
    records.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { record ->
                val photo by produceState<ImageBitmap?>(null, record.getString("id")) {
                    try { value = WardrobeRepository.thumbnail(context, record.getString("image_url"), localStore = store) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { value = null }
                }
                Surface(onClick = { onClick?.invoke(record) }, enabled = onClick != null, shape = RoundedCornerShape(14.dp),
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
