package com.swpp.stylemate.ui.tryon

import android.content.ContentValues
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.tryon.*
import androidx.core.content.ContextCompat
import com.swpp.stylemate.ui.profile.decodeDownscaled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

val LocalFittingNotificationKey = staticCompositionLocalOf<String?> { null }

@Composable
fun FittingPreview(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onExpand, modifier = modifier.padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp)) { Text("가상 피팅") }
}

@Composable
fun FittingDialog(
    outfit: Outfit,
    repository: FittingRepository,
    account: AccountAccess,
    onClose: () -> Unit,
    profile: BodyProfile? = null,
    initialSource: String? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            key(account.userId, outfit.id, outfit.items) {
                PhotoFitting(outfit, repository, account, profile, onClose, initialSource)
            }
        }
    }
}

@Composable
internal fun PhotoFitting(outfit: Outfit, repository: FittingRepository, account: AccountAccess,
                         profile: BodyProfile?, onClose: () -> Unit, initialSource: String?) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val work = remember(context) { FittingWork(context) }
    val virtualModel = remember(profile) { VirtualFittingModels.closest(profile?.input) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var needsBody by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var notificationSettings by remember { mutableStateOf(FittingNotificationSettings()) }
    var changingNotification by remember { mutableStateOf(false) }
    var requestingNotificationPermission by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            try {
                val selected = notificationSettings.copy(enabled = granted)
                repository.saveNotificationSettings(selected)
                notificationSettings = selected
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failure = "알림 설정을 저장하지 못했어요. 다시 시도해 주세요." }
            finally { requestingNotificationPermission = false }
        }
    }
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (requestingNotificationPermission) return
            requestingNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (!FittingNotifications.allowed(context)) Toast.makeText(context,
                "휴대폰 설정에서 StyleMate 알림을 허용해 주세요.", Toast.LENGTH_LONG).show()
        }
    }
    fun saveNotificationChoice(enabled: Boolean) {
        if (changingNotification) return
        changingNotification = true
        scope.launch {
            try {
                val selected = FittingNotificationSettings(enabled)
                repository.saveNotificationSettings(selected)
                notificationSettings = selected
                if (selected.enabled) requestNotificationPermission()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failure = "알림 설정을 저장하지 못했어요. 다시 시도해 주세요." }
            finally { changingNotification = false }
        }
    }

    fun generate(source: String, person: suspend () -> ByteArray) {
        if (busy) return
        busy = true
        result = null
        failure = null
        saved = false
        scope.launch(Dispatchers.Main.immediate) {
            try {
                work.enqueue(outfit, repository, account.userId, source, person)
                Toast.makeText(context, if (notificationSettings.enabled && FittingNotifications.allowed(context))
                    "가상 피팅을 만드는 중입니다. 완료되면 알림을 보내드립니다."
                    else "가상 피팅을 만드는 중입니다. 완료 후 코디에서 결과를 확인해 주세요.", Toast.LENGTH_LONG).show()
                onClose()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { failure = "피팅 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." }
            catch (_: IllegalArgumentException) { failure = "코디의 옷 정보나 사진을 확인해 주세요." }
            catch (error: Exception) { failure = error.message ?: "피팅 생성에 실패했어요. 다시 시도해 주세요." }
            finally { busy = false }
        }
    }

    fun saveResult(destination: Uri? = null) {
        val image = result ?: return
        if (saving || saved) return
        saving = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                if (destination == null) saveFittingImage(context, image)
                else writeFittingImage(context, destination, image)
                saved = true
                Toast.makeText(context, "피팅 이미지를 저장했어요.", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, "저장하지 못했어요. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show() }
            finally { saving = false }
        }
    }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) saveResult(uri)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val agreed = consent
        consent = false
        if (uri != null && agreed && !busy) {
            generate("gallery") {
                val photo = decodeDownscaled(context, uri) ?: error("사진을 읽지 못했어요. 다른 사진을 선택해 주세요.")
                try { photo.fittingJpeg() } finally { photo.recycle() }
            }
        }
    }
    fun openSource(source: String, onMissing: () -> Unit) {
        if (busy || restoring || changingNotification || requestingNotificationPermission) return
        restoring = true
        failure = null
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val output = try { repository.result(outfit, source) } catch (_: IOException) { null }
                val cached = output?.let { decodeFitting(it) }
                if (cached != null) { result = cached; saved = false }
                else onMissing()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "피팅 결과를 읽지 못했어요." }
            finally { restoring = false }
        }
    }
    fun useVirtual(model: VirtualFittingModel) {
        needsBody = false
        openSource(model.sourceKey) {
            generate(model.sourceKey) {
                withContext(Dispatchers.IO) {
                    context.assets.open(model.assetPath).use { it.readBytes() }
                }
            }
        }
    }

    LaunchedEffect(outfit, initialSource) {
        restoring = true
        try {
            notificationSettings = repository.notificationSettings()
            if (notificationSettings.enabled && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission()
            }
            do {
                val task = work.state(repository.taskKey(outfit))
                pending = task?.optString("state") in listOf("queued", "running")
                if (task?.optString("state") == "failed") failure = task.optString("error")
                if (!pending && task?.optString("state") != "failed") {
                    val source = initialSource ?: repository.latestSource(outfit)
                    if (source != null) result = repository.result(outfit, source)?.let { decodeFitting(it) }
                }
                restoring = false
                if (pending) delay(1_000)
            } while (pending)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failure = "피팅 결과를 읽지 못했어요. 다시 시도해 주세요." }
        finally { restoring = false }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (result == null) TextButton(onClick = onClose) { Text("닫기") }
            else Spacer(Modifier.size(64.dp, 48.dp))
            Text("가상 피팅", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            FittingNotificationToggle(notificationSettings.enabled && FittingNotifications.allowed(context),
                onCheckedChange = ::saveNotificationChoice,
                enabled = !restoring && !changingNotification && !requestingNotificationPermission, modifier = Modifier.width(64.dp))
        }
        when {
            restoring -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(40.dp))
            }
            needsBody -> VirtualModelInput(profile?.input, onBack = { needsBody = false },
                onContinue = ::useVirtual, modifier = Modifier.weight(1f))
            else -> PhotoFittingContent(busy, result, failure, onChoosePhoto = {
                openSource("gallery") {
                    consent = true
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }, onChooseVirtual = {
                if (virtualModel == null) needsBody = true else useVirtual(virtualModel)
            }, onSaveResult = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveResult()
                else saveDocument.launch("StyleMate-${UUID.randomUUID()}.png")
            }, onConfirm = onClose, modifier = Modifier.weight(1f), saving = saving, saved = saved,
                virtualModelLabel = virtualModel?.label, pending = pending,
                notificationsEnabled = notificationSettings.enabled && FittingNotifications.allowed(context))
        }
    }
}

@Composable
internal fun FittingNotificationToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit,
                                      modifier: Modifier = Modifier, enabled: Boolean = true) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(checked) {
        if (checked) {
            angle.animateTo(-12f, tween(60))
            angle.animateTo(12f, tween(90))
        }
        angle.animateTo(0f, tween(90))
    }
    IconToggleButton(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = "피팅 완료 알림"
            stateDescription = if (checked) "켜짐" else "꺼짐"
            role = Role.Switch
        }) {
        val color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.size(28.dp).graphicsLayer { rotationZ = angle.value }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Notifications, null, Modifier.size(22.dp), tint = color)
            if (checked) Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
                drawArc(color, 135f, 90f, false, Offset(0f, 4.dp.toPx()), Size(8.dp.toPx(), 13.dp.toPx()), style = stroke)
                drawArc(color, 315f, 90f, false, Offset(20.dp.toPx(), 4.dp.toPx()), Size(8.dp.toPx(), 13.dp.toPx()), style = stroke)
            }
        }
    }
}

@Composable
internal fun PhotoFittingContent(
    busy: Boolean,
    result: Bitmap?,
    failure: String?,
    onChoosePhoto: () -> Unit,
    onChooseVirtual: () -> Unit,
    onSaveResult: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    saved: Boolean = false,
    virtualModelLabel: String? = null,
    pending: Boolean = false,
    notificationsEnabled: Boolean = true,
) {
    Box(modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        when {
            pending -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("가상 피팅을 만드는 중입니다.", style = MaterialTheme.typography.titleMedium)
                Text(if (notificationsEnabled) "완료되면 알림을 보내드립니다."
                    else "완료 후 코디에서 결과를 확인해 주세요.", textAlign = TextAlign.Center)
                Button(onClick = onConfirm) { Text("확인") }
            }
            busy -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(40.dp))
                Text("사진을 준비하고 있습니다.", style = MaterialTheme.typography.bodyLarge)
            }
            result != null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Image(result.asImageBitmap(), "생성된 피팅 이미지",
                    Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSaveResult, enabled = !saving && !saved,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp)) { Text(if (saving) "저장 중…" else "저장") }
                    Button(onClick = onConfirm, enabled = !saving,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp)) { Text("확인") }
                }
            }
            else -> Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                failure?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error) }
                Text("어떤 사진으로 입혀볼까요?", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onChoosePhoto, modifier = Modifier.weight(1f).heightIn(min = 136.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Text("내 갤러리에서\n사진 고르기", textAlign = TextAlign.Center)
                    }
                    Button(onClick = onChooseVirtual, modifier = Modifier.weight(1f).heightIn(min = 136.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Text("내 체형과 비슷한\n가상 모델에게 입히기", textAlign = TextAlign.Center)
                    }
                }
                Text(if (virtualModelLabel == null) "가상 모델은 성별·키·몸무게만 입력해도 이용할 수 있어요."
                    else "가상 모델: $virtualModelLabel", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("사진을 선택하면 피팅을 위해 OpenRouter·Google로 전송하는 데 동의합니다.\nStyleMate 서버의 입력 사진은 처리 후 삭제되며 외부 서비스의 보관 정책이 적용됩니다.\n생성된 이미지는 이 기기에 보관됩니다.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FittingNotificationResult(taskKey: String?, account: AccountAccess, onHandled: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var task by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(taskKey, account.userId) {
        task = null
        if (taskKey == null) return@LaunchedEffect
        try {
            val loaded = FittingWork(context).state(taskKey)
            if (loaded != null && loaded.optString("owner") == account.userId) task = loaded
            else {
                Toast.makeText(context, "피팅을 요청한 계정에서 결과를 확인해 주세요.", Toast.LENGTH_LONG).show()
                onHandled()
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { onHandled() }
    }
    task?.let { data ->
        val fitting = remember(account) { FittingRepository(com.swpp.stylemate.data.LocalUserStore.forContext(context, account.userId), account) }
        FittingDialog(FittingTaskStore.outfit(data), fitting, account, onClose = { task = null; onHandled() },
            initialSource = data.getString("source"))
    }
}

@Composable
private fun VirtualModelInput(initial: AnalysisInput?, onBack: () -> Unit,
                              onContinue: (VirtualFittingModel) -> Unit, modifier: Modifier = Modifier) {
    var gender by remember { mutableStateOf(initial?.gender ?: Gender.UNSPECIFIED) }
    var height by remember { mutableStateOf(initial?.heightCm?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(initial?.weightKg?.toString().orEmpty()) }
    val candidate = VirtualFittingModels.closest(AnalysisInput(height.toIntOrNull() ?: 0, weight.toIntOrNull(), gender))
    Column(modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("사진 없이 체형만 알려주세요.", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Gender.MALE, Gender.FEMALE).forEach { value ->
                FilterChip(selected = gender == value, onClick = { gender = value }, label = { Text(value.label) })
            }
        }
        OutlinedTextField(height, { height = it.filter(Char::isDigit).take(3) }, label = { Text("키 (cm)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(weight, { weight = it.filter(Char::isDigit).take(3) }, label = { Text("몸무게 (kg)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        candidate?.let { Text("비슷한 가상 모델: ${it.label}", style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { candidate?.let(onContinue) }, enabled = candidate != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
            Text("이 모델에게 입히기")
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("사진 선택 방식으로 돌아가기") }
    }
}

private suspend fun decodeFitting(bytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

internal suspend fun saveFittingImage(context: Context, image: Bitmap): Uri = withContext(Dispatchers.IO) {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "StyleMate-${UUID.randomUUID()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StyleMate")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Unable to create fitting image")
    try {
        writeFittingImage(context, uri, image)
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        uri
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private suspend fun writeFittingImage(context: Context, uri: Uri, image: Bitmap) = withContext(Dispatchers.IO) {
    val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Unable to open fitting image")
    output.use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}

private suspend fun Bitmap.fittingJpeg(): ByteArray = withContext(Dispatchers.Default) {
    ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.JPEG, 95, output)) { "사진을 준비하지 못했어요. 다시 선택해 주세요." }
        output.toByteArray()
    }
}
