package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.components.ScreenScaffold
import org.json.JSONObject

/** Shows owned garments before the optional shopping search; does not alter recommendation scores. */
@Composable
internal fun GarmentMatchScreen(source: JSONObject, state: ExternalMatchState, records: List<JSONObject>, catalog: JSONObject?,
    linkError: String?, onBack: () -> Unit, onRetry: () -> Unit, onItem: (String) -> Unit, onOpenLink: (String) -> Unit,
    photo: @Composable (JSONObject, Modifier) -> Unit = { record, modifier -> Box(modifier) {
        GarmentPhoto(record.optString("image_url"), record.getJSONObject("attributes").optString("name"), framed = false)
    } }) {
    val sourceName = source.getJSONObject("attributes").optString("name")
    val result = state.result
    val matches = result?.wardrobeMatches.orEmpty().mapNotNull { match ->
        records.firstOrNull { it.optString("id") == match.garmentId }
    }
    ScreenScaffold("함께 입을 옷", null, false, {}, onBack = onBack, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("garment-match-list"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("source") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.size(72.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        photo(source, Modifier.padding(6.dp).fillMaxSize())
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("이 옷과 함께", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sourceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            when {
                state.loading || (result == null && state.error == null) -> item("loading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("함께 입을 옷을 찾고 있어요.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error != null -> item("error") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRetry, shape = MaterialTheme.shapes.medium) { Text("다시 찾기") }
                    }
                }
                result != null -> {
                    item("heading") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("내 옷장에서 함께 입기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (matches.isEmpty()) "함께 입을 옷이 아직 없어요."
                                else "이 옷과 함께 입을 수 있는 등록 옷이에요.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(matches, key = { it.optString("id") }) { record ->
                        val attrs = record.getJSONObject("attributes")
                        val name = attrs.optString("name")
                        Surface(onClick = { onItem(record.getString("id")) }, shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                photo(record, Modifier.size(80.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(wardrobeCategoryName(attrs.optString("category"), catalog),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("옷 보기", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item("shopping") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(Modifier.testTag("garment-shopping")) {
                                GarmentSection("새 옷 찾아보기") {
                                    Text("이런 옷과 잘 어울려요", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("선택한 옷을 기준으로 고른 색상과 핏이에요.", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { onOpenLink(result.searchUrl) }, shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("무신사에서 더 알아보기 ↗") }
                                    linkError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
