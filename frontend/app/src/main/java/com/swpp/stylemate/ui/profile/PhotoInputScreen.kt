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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
) {
    ScreenScaffold(
        title = "체형 분석",
        onBack = onBack,
        primaryLabel = "분석하기",
        primaryEnabled = state.canAnalyze,
        onPrimary = onAnalyze,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            state.errorMessage?.let { WarningBanner(it) }
            SectionTitle("전신 사진", "정면 · 측면 모두 필요")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhotoSlotCard(
                    label = "정면",
                    bitmap = state.frontPhoto,
                    onPicked = { onPhoto(PhotoSlot.FRONT, it) },
                    onPickFailed = { onPhotoError(PHOTO_LOAD_ERROR) },
                    modifier = Modifier.weight(1f),
                )
                PhotoSlotCard(
                    label = "측면",
                    bitmap = state.sidePhoto,
                    onPicked = { onPhoto(PhotoSlot.SIDE, it) },
                    onPickFailed = { onPhotoError(PHOTO_LOAD_ERROR) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            ClothingTip()

            SectionTitle("기본 정보", "키는 치수 계산의 기준이 돼요")
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
    onPickFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Full-resolution capture into a temporary cache file; the file is deleted right after decoding.
    val captureFile = remember { File(context.cacheDir, "body_photos/$label.jpg") }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        scope.launch {
            if (saved) decodeDownscaled(context, Uri.fromFile(captureFile))?.let(onPicked) ?: onPickFailed()
            withContext(Dispatchers.IO) { captureFile.delete() }
        }
    }
    fun capture() {
        captureFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", captureFile)
        try { cameraLauncher.launch(uri) }
        catch (_: Exception) { captureFile.delete(); onPickFailed() }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture() else onPickFailed()
    }
    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) capture()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // A failed decode keeps the previous photo and shows a message instead of clearing it.
        if (uri != null) scope.launch { decodeDownscaled(context, uri)?.let(onPicked) ?: onPickFailed() }
    }

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
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
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f)),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "$label 사진 지우기", modifier = Modifier.size(18.dp))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "필수",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallOutlinedButton("카메라", Modifier.weight(1f)) { launchCamera() }
            SmallOutlinedButton("앨범", Modifier.weight(1f)) {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }
}

@Composable
private fun SmallOutlinedButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Clothing is detected automatically; this only tells users what gives the best result. */
@Composable
private fun ClothingTip() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
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

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    supporting: String?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
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
private suspend fun decodeDownscaled(context: Context, uri: Uri, maxSide: Int = 1280): Bitmap? =
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
