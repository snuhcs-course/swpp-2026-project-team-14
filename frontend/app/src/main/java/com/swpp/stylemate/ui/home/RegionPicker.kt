// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-27, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.stylemate.data.recommendation.HomeRegion
import com.swpp.stylemate.data.recommendation.Region
import com.swpp.stylemate.data.recommendation.Weather
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

private val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

/** The initial consonants of Hangul syllables ("부산" → "ㅂㅅ"); other characters stay as they are. */
internal fun choseong(text: String) = text.map { c ->
    if (c in '가'..'힣') CHOSEONG[(c - '가') / 588] else c
}.joinToString("")

/** A region matches by name ("부", "부산") or by its initial consonants ("ㅂㅅ"). */
internal fun matchesRegion(query: String, name: String): Boolean {
    val q = query.trim().replace(" ", "")
    if (q.isEmpty()) return true
    return name.contains(q) || (q.all { it in CHOSEONG } && choseong(name).contains(q))
}

/** What to draw for a weather label from the server (맑음, 대체로 맑음, 구름 조금, 흐림, 안개, 비, 눈). */
internal enum class Sky { CLEAR, PARTLY, CLOUDY, RAIN, SNOW }

internal fun skyOf(weather: Weather): Sky = when {
    weather.snow || weather.label.contains("눈") -> Sky.SNOW
    weather.rain || weather.label.contains("비") -> Sky.RAIN
    weather.label == "맑음" -> Sky.CLEAR
    weather.label.contains("맑음") || weather.label.contains("구름 조금") -> Sky.PARTLY
    else -> Sky.CLOUDY
}

/**
 * Where a place falls on the small map: an equirectangular view of South Korea, with longitude narrowed by
 * the cosine of the middle latitude so the peninsula keeps its shape, fitted and centred in [size].
 */
internal fun projectKorea(lat: Double, lon: Double, size: Size, inset: Float = 0f): Offset {
    val west = 125.9; val east = 129.75; val south = 33.0; val north = 38.75
    val squeeze = cos(Math.toRadians(36.0))
    val spanX = (east - west) * squeeze; val spanY = north - south
    val scale = minOf((size.width - inset * 2) / spanX, (size.height - inset * 2) / spanY)
    val left = (size.width - spanX * scale) / 2; val top = (size.height - spanY * scale) / 2
    return Offset((left + (lon - west) * squeeze * scale).toFloat(), (top + (north - lat) * scale).toFloat())
}

// A simplified outline of the mainland (latitude, longitude), clockwise from Ganghwa, and Jeju as an ellipse.
private val MAINLAND = listOf(
    37.75 to 126.45, 38.0 to 126.95, 38.15 to 127.45, 38.32 to 127.95, 38.62 to 128.35, 38.2 to 128.62,
    37.75 to 128.92, 37.35 to 129.27, 36.95 to 129.42, 36.4 to 129.4, 36.05 to 129.57, 35.55 to 129.45,
    35.1 to 129.1, 35.0 to 128.7, 34.85 to 128.4, 34.9 to 127.9, 34.7 to 127.7, 34.55 to 127.35,
    34.45 to 126.9, 34.3 to 126.55, 34.7 to 126.3, 35.1 to 126.35, 35.6 to 126.45, 35.95 to 126.65,
    36.3 to 126.5, 36.75 to 126.15, 37.0 to 126.75, 37.2 to 126.65, 37.45 to 126.55,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegionSheet(
    current: String,
    currentWeather: Weather?,
    load: suspend (Region) -> Weather?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val weathers = remember { mutableStateMapOf<String, Weather>().apply { currentWeather?.let { put(current, it) } } }
    var chosen by remember { mutableStateOf(current) }
    var query by remember { mutableStateOf("") }
    // Every region's weather at once (the server caches each for 10 minutes), a few requests at a time.
    LaunchedEffect(Unit) {
        val gate = Semaphore(4)
        coroutineScope {
            HomeRegion.OPTIONS.filter { it.name !in weathers }.map { region ->
                async { gate.withPermit { load(region)?.let { weathers[region.name] = it } } }
            }.awaitAll()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text("날씨를 볼 지역", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("오늘의 코디를 이 지역 날씨로 골라요. 여행지는 대화에서 \"내일 부산\"처럼 말해 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(272.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KoreaWeatherMap(chosen, weathers, onChoose = { chosen = it }, modifier = Modifier.weight(.56f).fillMaxSize())
                ChosenWeather(chosen, weathers[chosen], Modifier.weight(.44f).fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(query, { query = it.take(10) }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("지역 검색 (예: 부산, ㅂㅅ)") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
            val shown = HomeRegion.OPTIONS.filter { matchesRegion(query, it.name) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 6.dp)) {
                items(shown, key = { it.name }) { region ->
                    RegionRow(region.name, weathers[region.name], region.name == chosen, region.name == current) { chosen = region.name }
                }
                if (shown.isEmpty()) item {
                    Text("'$query'에 맞는 지역이 없어요", Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onPick(chosen) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.medium) {
                Text(if (chosen == current) "$chosen 날씨 그대로 보기" else "$chosen 날씨로 추천받기")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The regions as dots on the peninsula, each with its temperature; the chosen one pulses. Tap a dot to choose. */
@Composable
private fun KoreaWeatherMap(chosen: String, weathers: Map<String, Weather>, onChoose: (String) -> Unit, modifier: Modifier) {
    val land = MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)
    val coast = MaterialTheme.colorScheme.onSurface.copy(alpha = .22f)
    val ink = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "chosen").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "pulse")
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .035f))
        .semantics { contentDescription = "지역 지도, ${chosen} 선택됨" }) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { tap ->
                val sized = Size(size.width.toFloat(), size.height.toFloat())
                val nearest = HomeRegion.OPTIONS.minByOrNull { r ->
                    val p = projectKorea(r.lat, r.lon, sized, 14.dp.toPx()); hypot(p.x - tap.x, p.y - tap.y)
                }
                if (nearest != null) {
                    val p = projectKorea(nearest.lat, nearest.lon, sized, 14.dp.toPx())
                    if (hypot(p.x - tap.x, p.y - tap.y) < 28.dp.toPx()) onChoose(nearest.name)
                }
            }
        }) {
            val inset = 14.dp.toPx()
            fun at(lat: Double, lon: Double) = projectKorea(lat, lon, size, inset)
            // Land: the outline smoothed through the midpoints of its edges.
            val points = MAINLAND.map { (lat, lon) -> at(lat, lon) }
            val shape = Path().apply {
                val start = (points.last() + points.first()) / 2f
                moveTo(start.x, start.y)
                points.indices.forEach { i ->
                    val p = points[i]; val next = points[(i + 1) % points.size]; val mid = (p + next) / 2f
                    quadraticTo(p.x, p.y, mid.x, mid.y)
                }
                close()
            }
            drawPath(shape, land)
            drawPath(shape, coast, style = Stroke(1.dp.toPx()))
            val jeju = at(33.38, 126.55); val jejuEdge = at(33.38 + .13, 126.55 + .33)
            val radius = Size((jejuEdge.x - jeju.x) * 2, (jeju.y - jejuEdge.y) * 2)
            drawOval(land, jeju - Offset(radius.width / 2, radius.height / 2), radius)
            drawOval(coast, jeju - Offset(radius.width / 2, radius.height / 2), radius, style = Stroke(1.dp.toPx()))
            // Dots first, then labels placed greedily so none overlaps: the chosen region first, each in the
            // first free spot (right, left, above, below) inside the map; one with no free spot is left out.
            val dots = HomeRegion.OPTIONS.associate { it.name to at(it.lat, it.lon) }
            dots.forEach { (name, p) ->
                if (name == chosen) {
                    drawCircle(ink.copy(alpha = .28f * (1f - pulse)), 6.dp.toPx() + 12.dp.toPx() * pulse, p)
                    drawCircle(ink, 5.dp.toPx(), p)
                    drawCircle(Color.White, 2.dp.toPx(), p)
                } else drawCircle(muted.copy(alpha = .75f), 3.dp.toPx(), p)
            }
            val placed = dots.values.map { Rect(it - Offset(4.dp.toPx(), 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx())) }
                .toMutableList()
            val bounds = Rect(Offset.Zero, size)
            dots.keys.sortedByDescending { it == chosen }.forEach { name ->
                val temp = weathers[name]?.let { "${Math.round(it.temp)}°" } ?: return@forEach
                val isChosen = name == chosen
                val style = TextStyle(fontSize = if (isChosen) 11.sp else 9.sp,
                    fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium, color = if (isChosen) ink else muted)
                val measured = text.measure(if (isChosen) "$name $temp" else temp, style)
                val w = measured.size.width.toFloat(); val h = measured.size.height.toFloat()
                val p = dots.getValue(name); val gap = 6.dp.toPx()
                val spot = listOf(Offset(p.x + gap, p.y - h / 2), Offset(p.x - gap - w, p.y - h / 2),
                    Offset(p.x - w / 2, p.y - gap - h), Offset(p.x - w / 2, p.y + gap))
                    .map { Rect(it, Size(w, h)) }
                    .firstOrNull { r -> bounds.contains(r.topLeft) && bounds.contains(r.bottomRight) && placed.none { it.overlaps(r) } }
                    ?: return@forEach
                placed += spot
                drawText(measured, topLeft = spot.topLeft)
            }
        }
    }
}

/** The chosen region's weather, large: its sky drawn, the temperature, the label, how it feels and the band. */
@Composable
private fun ChosenWeather(name: String, weather: Weather?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .035f))
        .padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("지금 날씨", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (weather == null) Text("날씨를 불러오는 중이에요", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            SkyIcon(skyOf(weather), Modifier.size(52.dp))
            Column {
                Text("${Math.round(weather.temp)}°", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(weather.label.ifBlank { null },
                    if (kotlin.math.abs(weather.apparent - weather.temp) >= 2) "체감 ${Math.round(weather.apparent)}°" else null)
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (weather.band.isNotBlank()) Text(weather.band, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RegionRow(name: String, weather: Weather?, chosen: Boolean, current: Boolean, onClick: () -> Unit) {
    val back by animateColorAsState(if (chosen) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, label = "row")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(back)
        .clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = chosen }
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
        if (current) Text("지금 지역", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (weather != null) {
            SkyIcon(skyOf(weather), Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(String.format(Locale.KOREA, "%d°", Math.round(weather.temp)), Modifier.width(34.dp),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        } else Text("…", Modifier.width(62.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val check by animateFloatAsState(if (chosen) 1f else 0f, label = "check")
        Icon(Icons.Filled.Check, null, Modifier.size(18.dp).padding(start = 2.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = check))
    }
}

/** Small line-drawn weather glyphs in the theme's ink, so they sit with the app's black and grey. */
@Composable
internal fun SkyIcon(sky: Sky, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) { drawSky(sky, ink) }
}

private fun DrawScope.drawSky(sky: Sky, ink: Color) {
    val w = size.minDimension
    val line = Stroke(w * .07f, cap = StrokeCap.Round)
    fun sun(center: Offset, r: Float) {
        drawCircle(ink, r, center, style = line)
        repeat(8) { i ->
            val a = Math.toRadians(i * 45.0)
            val dir = Offset(cos(a).toFloat(), kotlin.math.sin(a).toFloat())
            drawLine(ink, center + dir * (r * 1.45f), center + dir * (r * 1.85f), line.width, StrokeCap.Round)
        }
    }
    fun cloud(origin: Offset, scale: Float) {
        val path = Path().apply {
            moveTo(origin.x + .18f * scale, origin.y + .62f * scale)
            cubicTo(origin.x, origin.y + .62f * scale, origin.x, origin.y + .3f * scale, origin.x + .22f * scale, origin.y + .32f * scale)
            cubicTo(origin.x + .26f * scale, origin.y + .05f * scale, origin.x + .62f * scale, origin.y, origin.x + .7f * scale, origin.y + .26f * scale)
            cubicTo(origin.x + .95f * scale, origin.y + .22f * scale, origin.x + 1.02f * scale, origin.y + .62f * scale, origin.x + .8f * scale, origin.y + .62f * scale)
            close()
        }
        drawPath(path, Color.White.copy(alpha = 0f))
        drawPath(path, ink, style = line)
    }
    when (sky) {
        Sky.CLEAR -> sun(Offset(w / 2, w / 2), w * .2f)
        Sky.PARTLY -> { sun(Offset(w * .36f, w * .34f), w * .14f); cloud(Offset(w * .2f, w * .36f), w * .78f) }
        Sky.CLOUDY -> cloud(Offset(w * .08f, w * .2f), w * .86f)
        Sky.RAIN -> {
            cloud(Offset(w * .08f, w * .08f), w * .86f)
            listOf(.32f, .52f, .72f).forEach { x -> drawLine(ink, Offset(w * x, w * .7f), Offset(w * (x - .06f), w * .9f), line.width, StrokeCap.Round) }
        }
        Sky.SNOW -> {
            cloud(Offset(w * .08f, w * .08f), w * .86f)
            listOf(.32f, .52f, .72f).forEach { x -> drawCircle(ink, w * .045f, Offset(w * x, w * .82f)) }
        }
    }
}
