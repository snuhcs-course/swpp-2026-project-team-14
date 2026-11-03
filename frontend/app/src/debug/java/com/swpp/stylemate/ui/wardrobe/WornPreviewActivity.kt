package com.swpp.stylemate.ui.wardrobe

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.swpp.stylemate.ui.theme.StyleMateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Debug-only, offline fixture viewer. No AccountAccess, repository, ViewModel or HTTP client. */
class WornPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        var fixture by mutableStateOf<WornUiFixture?>(null)
        var failed by mutableStateOf(false)
        lifecycleScope.launch {
            try { fixture = withContext(Dispatchers.IO) { loadFixture(File(filesDir, "worn-ui-preview")) } }
            catch (_: Exception) { failed = true }
        }
        setContent { StyleMateTheme {
            val ready = fixture
            if (ready != null) WornUiPreview(ready, onClose = ::finish)
            else Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (failed) "로컬 미리보기 자료가 없습니다. API는 호출하지 않았어요." else "로컬 미리보기 자료 읽는 중")
                }
            }
        } }
    }
}

internal data class WornUiFixture(val items: List<WornBatchItem>, val catalog: JSONObject,
                                  val images: Map<String, ImageBitmap>)

private fun loadFixture(directory: File): WornUiFixture {
    val file = File(directory, "fixture.json")
    check(file.length() in 1..256 * 1024)
    val data = JSONObject(file.readText())
    val rows = data.getJSONArray("items")
    check(rows.length() in 1..3)
    val items = (0 until rows.length()).map { index ->
        val record = rows.getJSONObject(index)
        WornBatchItem(record, record.getJSONObject("generation"), JSONObject()
            .put("attributes", JSONObject(record.getJSONObject("attributes").toString()))
            .put("dimensions", JSONObject.NULL).put("notes", record.optString("notes"))
            .put("labels", JSONObject(record.getJSONObject("labels").toString())), imageChoice = "generated")
    }
    val images = data.getJSONObject("images")
    val bitmaps = images.keys().asSequence().associateWith { key ->
        val name = images.getString(key)
        require(name.matches(Regex("[a-z0-9_-]+\\.(jpg|png)")))
        val image = File(directory, name)
        check(image.length() in 1..8 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 12_000_000)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1200) inSampleSize *= 2
        }
        checkNotNull(BitmapFactory.decodeFile(image.path, options)).asImageBitmap()
    }
    return WornUiFixture(items, data.getJSONObject("catalog"), bitmaps)
}

@Composable
internal fun WornUiPreview(fixture: WornUiFixture, onClose: () -> Unit = {}) {
    var items by remember(fixture) { mutableStateOf(fixture.items) }
    var scenario by remember { mutableStateOf("완료") }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var cropId by remember { mutableStateOf<String?>(null) }
    fun update(id: String, action: (WornBatchItem) -> WornBatchItem) {
        items = items.map { if (it.id == id) action(it) else it }
    }
    val photo: WornPhotoContent = { label, path, _, modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Surface(Modifier.fillMaxWidth().aspectRatio(.7f), color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(contentAlignment = Alignment.Center) {
                    val bitmap = fixture.images[path]
                    if (bitmap != null) Image(bitmap, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text("생성 이미지 없음")
                }
            }
        }
    }
    val edit = items.firstOrNull { it.id == editing }
    if (edit != null) {
        PreviewEditor(edit, fixture.catalog, onBack = { editing = null }, photo = photo) { payload ->
            update(edit.id) { it.copy(payload = payload) }
            editing = null
        }
    } else WornPreviewReviewScreen(items, fixture.catalog, fixture.images, scenario = scenario,
        onBack = onClose,
        onScenario = { title ->
            scenario = title
            items = fixture.items.mapIndexed { index, item ->
                when {
                    title == "일부 실패" && index == 1 -> item.copy(
                        generation = JSONObject(item.generation.toString()).put("status", "failed").put("error", "GENERATION_RATE_LIMITED"), imageChoice = null)
                    title == "정보 미정" -> {
                        val payload = JSONObject(item.payload.toString()).apply { getJSONObject("labels").put("fit", JSONObject.NULL).put("length", JSONObject.NULL) }
                        item.copy(payload = payload)
                    }
                    else -> item
                }
            }
        },
        onSave = { message = "${items.count { it.included }}벌 등록 동작을 확인했어요. 실제 저장은 하지 않았어요." },
        onInclude = { id, included -> update(id) { it.copy(included = included) } },
        onCrop = { cropId = it },
        onRegenerate = { id ->
            update(id) { it.copy(generation = JSONObject(it.generation.toString()).put("status", "ready").put("error", JSONObject.NULL),
                imageChoice = "generated", error = null) }
            message = "기존 이미지를 다시 표시했어요. 이미지 생성 API는 호출하지 않았어요."
        },
        onEdit = { editing = it })
    cropId?.let { id -> AlertDialog(onDismissRequest = { cropId = null }, title = { Text("착용 사진 사용") },
        text = { Text("로컬 예시의 원본 사진으로 전환합니다. 실제 저장은 하지 않아요.") },
        confirmButton = { TextButton(onClick = { update(id) { it.copy(imageChoice = "crop") }; cropId = null }) { Text("착용 사진 사용") } },
        dismissButton = { TextButton(onClick = { cropId = null }) { Text("취소") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("미리보기") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("확인") } }) }
}

/** The real attribute editor; labels are edited locally instead of invoking label/recommendation APIs. */
@Composable
private fun PreviewEditor(item: WornBatchItem, catalog: JSONObject, onBack: () -> Unit,
                           photo: WornPhotoContent, onEdit: (JSONObject) -> Unit) {
    var labelsText by remember(item.id) { mutableStateOf(item.payload.getJSONObject("labels").toString()) }
    val labels = JSONObject(labelsText)
    GarmentEditor(item.id, item.payload.getJSONObject("attributes"), null, item.payload.optString("notes"), catalog,
        busy = false, saved = false, error = null, onBack = onBack, measurementsEnabled = false, saveLabel = "변경 확인",
        estimatedDimensions = matchingWornEstimates(item.record, item.payload.getJSONObject("attributes"), labels),
        photo = {
            Text("UI 미리보기 · 변경한 값은 이 화면에서만 유지됩니다.")
            photo("원본 옷 영역", "/api/wardrobe/items/${item.id}/worn-image/original/", "local", Modifier.fillMaxWidth().height(180.dp))
        }, labels = { attributes, _ ->
            val allowed = catalog.getJSONObject("label_options").optJSONObject(attributes.optString("category"))
            if (allowed != null) {
                listOf("fit" to "핏", "length" to "기장", "sleeve" to "소매", "season" to "계절").forEach { (field, title) ->
                    val options = allowed.optJSONArray(field)
                    if (options != null) LabelChips(title, (0 until options.length()).map { options.getString(it) },
                        if (labels.isNull(field)) null else labels.optString(field), null, true) {
                        labelsText = JSONObject(labelsText).put(field, it ?: JSONObject.NULL).toString()
                    }
                }
                listOf("prints" to "무늬", "neckline" to "넥라인", "collar" to "옷깃", "design_details" to "디테일", "materials" to "소재").forEach { (field, title) ->
                    val options = allowed.optJSONArray(field)
                    if (options != null) {
                        val single = field in setOf("neckline", "collar")
                        val selected = if (single) listOfNotNull(if (labels.isNull(field)) null else labels.optString(field))
                            else labels.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                        val limit = if (single) 0 else catalog.getJSONObject("label_options").getJSONObject("limits").optInt(field, 2)
                        LabelMenu(title, (0 until options.length()).map { options.getString(it) }, selected, limit, true) {
                            labelsText = JSONObject(labelsText).put(field, if (single) it.firstOrNull() ?: JSONObject.NULL else org.json.JSONArray(it)).toString()
                        }
                    }
                }
            }
        }, onSave = { payload ->
            val category = payload.getJSONObject("attributes").optString("category")
            payload.put("labels", GarmentLabels.merge(labels, emptyMap(), category, catalog.optJSONObject("label_options")))
            onEdit(payload)
        })
}
