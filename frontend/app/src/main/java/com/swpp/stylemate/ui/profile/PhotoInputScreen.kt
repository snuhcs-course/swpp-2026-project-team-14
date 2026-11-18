// AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
package com.swpp.stylemate.ui.profile

import com.swpp.stylemate.ui.components.ScreenScaffold
import com.swpp.stylemate.ui.components.SectionTitle

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.ui.theme.StyleMateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PhotoInputScreen(
    state: SetupState,
    onBack: () -> Unit,
    onPhoto: (PhotoSlot, Bitmap?) -> Unit,
    onPhotoError: (String) -> Unit,
    onHeight: (String) -> Unit,
    onWeight: (String) -> Unit,
    onGender: (Gender) -> Unit,
    onAnalyze: () -> Unit,
    topBar: (@Composable () -> Unit)? = null,
) {
    var guideVisible by rememberSaveable { mutableStateOf(false) }
    if (guideVisible) {
        BackHandler { guideVisible = false }
        CaptureGuideScreen({ guideVisible = false }, { guideVisible = false }, returnToInput = true)
        return
    }
    ScreenScaffold(
        title = "체형 분석",
        onBack = onBack,
        primaryLabel = "분석하기",
        primaryEnabled = state.canAnalyze,
        onPrimary = onAnalyze,
        topBar = topBar,
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            state.errorMessage?.let { WarningBanner(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("전신 사진", "정면 · 측면")
                TextButton(onClick = { guideVisible = true }) { Text("촬영 예시") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhotoSlotCard(
                    label = "정면",
                    bitmap = state.frontPhoto,
                    onPicked = { onPhoto(PhotoSlot.FRONT, it) },
                    onPickFailed = onPhotoError,
                    modifier = Modifier.weight(1f),
                )
                PhotoSlotCard(
                    label = "측면",
                    bitmap = state.sidePhoto,
                    onPicked = { onPhoto(PhotoSlot.SIDE, it) },
                    onPickFailed = onPhotoError,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            ClothingTip()

            SectionTitle("기본 정보", "키로 치수를 계산해요")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    value = state.heightText,
                    onValueChange = onHeight,
                    label = "키 (cm) *",
                    isError = state.heightError,
                    supporting = if (state.heightError) "100~220 사이로 입력" else null,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = state.weightText,
                    onValueChange = onWeight,
                    label = "몸무게 (kg)",
                    isError = state.weightError,
                    supporting = if (state.weightError) "30~200 사이로 입력" else "선택",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            GenderSelector(selected = state.gender, onSelect = onGender)
            Spacer(Modifier.height(20.dp))
            PrivacyNote()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PhotoSlotCard(
    label: String,
    bitmap: Bitmap?,
    onPicked: (Bitmap?) -> Unit,
    onPickFailed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosingSource by rememberSaveable { mutableStateOf(false) }
    // Full-resolution capture into a temporary cache file; the file is deleted right after decoding.
    val captureFile = remember { File(context.cacheDir, "body_photos/$label.jpg") }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        scope.launch {
            if (saved) decodeDownscaled(context, Uri.fromFile(captureFile))?.let(onPicked) ?: onPickFailed(PHOTO_LOAD_ERROR)
            withContext(Dispatchers.IO) { captureFile.delete() }
        }
    }
    fun capture() {
        captureFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", captureFile)
        try { cameraLauncher.launch(uri) }
        catch (_: Exception) { captureFile.delete(); onPickFailed("카메라를 열지 못했어요. 앨범에서 사진을 골라 주세요.") }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture() else onPickFailed("카메라 권한이 꺼져 있어요. 기기 설정에서 허용하거나 앨범에서 사진을 골라 주세요.")
    }
    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) capture()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // A failed decode keeps the previous photo and shows a message instead of clearing it.
        if (uri != null) scope.launch { decodeDownscaled(context, uri)?.let(onPicked) ?: onPickFailed(PHOTO_LOAD_ERROR) }
    }

    if (choosingSource) AlertDialog(onDismissRequest = { choosingSource = false },
        title = { Text("$label 사진 선택") },
        text = { Text("머리부터 발끝까지 보이는 $label 전신 사진을 골라 주세요.") },
        confirmButton = { TextButton(onClick = { choosingSource = false
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text("앨범") } },
        dismissButton = { TextButton(onClick = { choosingSource = false; launchCamera() }) { Text("카메라") } })

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClickLabel = "사진 선택 또는 교체") { choosingSource = true }
                .semantics {
                    contentDescription = "$label 사진 선택"
                    stateDescription = if (bitmap == null) "필수 사진 없음" else "사진 있음, 교체 가능"
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$label 사진",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = { onPicked(null) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f)),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "$label 사진 지우기", modifier = Modifier.size(18.dp))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "눌러서 사진 선택 · 필수",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Clothing is detected automatically; this only tells users what gives the best result. */
@Composable
private fun ClothingTip() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("👕", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            "속옷이나 몸에 딱 붙는 옷을 입고 찍어야 가장 정확해요. 헐렁한 옷은 자동으로 감지해서 알려드려요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenderSelector(selected: Gender, onSelect: (Gender) -> Unit) {
    Text("성별 (치수 기준표 선택용)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Gender.entries.forEachIndexed { index, gender ->
            SegmentedButton(
                selected = gender == selected,
                onClick = { onSelect(gender) },
                shape = SegmentedButtonDefaults.itemShape(index, Gender.entries.size),
            ) {
                Text(gender.label)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    supporting: String?,
    modifier: Modifier = Modifier,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboardHeight, supporting) {
        if (focused) bringIntoView.bringIntoView()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.bringIntoViewRequester(bringIntoView).onFocusChanged { focused = it.isFocused },
    )
}

private const val PHOTO_LOAD_ERROR = "사진을 불러오지 못했어요. 다른 사진을 선택하거나 다시 찍어주세요."

/**
 * Decodes a photo at most ~1280 px on the long side (enough for body analysis) and turns it upright.
 * Phone cameras often store photos sideways with an EXIF orientation tag that BitmapFactory ignores;
 * a sideways body would be rejected by the pose model.
 * Returns null instead of throwing when the photo cannot be read (unsupported or broken file, a cloud
 * photo that is not downloaded, revoked access): an exception here would crash the app.
 */
/** Gallery or camera image, upright and downscaled; null if it cannot be read. Shared with the wardrobe front photo. */
internal suspend fun decodeDownscaled(context: Context, uri: Uri, maxSide: Int = 1280): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            decodeUpright(context, uri, maxSide)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

private fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // not an image we can read
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    val degrees = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    return if (degrees == 0) {
        bitmap
    } else {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun PhotoInputPreview() {
    StyleMateTheme {
        PhotoInputScreen(
            state = SetupState(heightText = "172"),
            onBack = {}, onPhoto = { _, _ -> }, onPhotoError = {}, onHeight = {}, onWeight = {},
            onGender = {}, onAnalyze = {},
        )
    }
}
