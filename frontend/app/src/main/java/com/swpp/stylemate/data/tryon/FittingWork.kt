package com.swpp.stylemate.data.tryon

import android.content.Context
import androidx.work.*
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.AuthSessionStore
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.KeystoreSessionStore
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.usage.RatingPrompts
import com.swpp.stylemate.data.usage.RatingTask
import com.swpp.stylemate.data.usage.UsageMetrics
import com.swpp.stylemate.data.usage.usageErrorCode
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import android.graphics.BitmapFactory

class FittingWork(private val context: Context) {
    val tasks = FittingTaskStore(File(context.noBackupFilesDir, "fitting-tasks"))
    suspend fun enqueue(outfit: Outfit, repository: FittingRepository, owner: String,
                        source: String, person: suspend () -> ByteArray) = withContext(Dispatchers.IO) {
        enqueueLock.withLock {
            val key = repository.taskKey(outfit)
            val manager = WorkManager.getInstance(context)
            if (manager.getWorkInfosForUniqueWork("fitting:$key").get().any { !it.state.isFinished }) return@withLock false
            // Several outfits may be fitted at once, up to a small limit (the server makes two at a time and queues the rest).
            val active = FittingTaskStore.running(tasks.all(owner)).count { (other, _) ->
                manager.getWorkInfosForUniqueWork("fitting:$other").get().any { !it.state.isFinished }
            }
            if (active >= FittingTaskStore.MAX_ACTIVE) throw TooManyFittings()
            val attempt = UsageMetrics.forAccount(context, owner).start("virtual_fitting", if (source == "gallery") "gallery" else "virtual")
            val work = OneTimeWorkRequestBuilder<FittingWorker>().setInputData(workDataOf("key" to key))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            try {
                val photo = person()
                tasks.create(key, owner, source, outfit, photo, attempt.checkpoint)
                if (VirtualFittingModels.all.none { it.sourceKey == source }) runCatching { smallJpeg(photo)?.let { tasks.savePreview(key, it) } }
                manager.enqueueUniqueWork("fitting:$key", ExistingWorkPolicy.KEEP, work).result.get()
            } catch (error: CancellationException) { attempt.cancel(); throw error }
            catch (error: Exception) {
                attempt.failure(usageErrorCode(error))
                if (tasks.read(key)?.optString("state") == "queued") tasks.finish(key, "작업을 시작하지 못했어요. 다시 시도해 주세요.")
                throw error
            }
            true
        }
    }
    suspend fun state(key: String): JSONObject? = withContext(Dispatchers.IO) {
        enqueueLock.withLock {
            val data = tasks.read(key) ?: return@withLock null
            if (data.optString("state") in listOf("queued", "running")) {
                val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork("fitting:$key").get().any { !it.state.isFinished }
                if (!active && tasks.read(key)?.optString("state") in listOf("queued", "running")) {
                    data.optJSONObject("usage")?.let { UsageMetrics.forAccount(context, data.getString("owner")).resume(it).cancel() }
                    tasks.finish(key, "피팅 작업이 중단됐어요. 사진을 다시 선택해 주세요.")
                }
            } else tasks.deletePerson(key)
            tasks.read(key)
        }
    }
    suspend fun displayed(key: String, owner: String, source: String) = withContext(Dispatchers.IO) {
        val data = tasks.read(key) ?: return@withContext
        if (data.optString("owner") != owner || data.optString("source") != source || data.optString("state") != "completed") return@withContext
        data.optJSONObject("usage")?.let { UsageMetrics.forAccount(context, owner).resume(it).success() }
        // 같은 결과를 다시 열 때는 묻지 않도록 쓰고 바로 지운다. 결과를 볼 시간을 두고 띄운다.
        if (data.optBoolean("rating_prompt")) {
            tasks.ratingPrompt(key, false)
            RatingPrompts.request(RatingTask.VIRTUAL_FITTING, owner, showAfterMs = 2_000)
        }
    }
    /** The photo downscaled to about 720 px for the on-device preview. */
    private fun smallJpeg(photo: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(photo, 0, photo.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
    companion object { private val enqueueLock = Mutex() }
}

class FittingWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val apiBaseUrl: String, private val accountProvider: (suspend () -> AccountAccess?)?) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, BuildConfig.API_BASE_URL, null)
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val key = inputData.getString("key").orEmpty()
        return ForegroundInfo(key.hashCode() xor Int.MIN_VALUE, FittingNotifications.notification(applicationContext, key, false))
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val key = inputData.getString("key") ?: return@withContext Result.failure()
        val tasks = FittingWork(applicationContext).tasks
        val data = tasks.read(key) ?: return@withContext Result.failure()
        val owner = data.getString("owner")
        val usage = data.optJSONObject("usage")?.let { UsageMetrics.forAccount(applicationContext, owner).resume(it) }
        try {
            val account = checkNotNull(currentAccount()) { "로그인 후 피팅을 다시 시도해 주세요." }
            check(account.userId == owner) { "계정이 변경되어 피팅을 중단했어요." }
            val outfit = FittingTaskStore.outfit(data)
            val repository = FittingRepository(LocalUserStore.forContext(applicationContext, account.userId), account)
            val source = data.getString("source")
            if (repository.result(outfit, source) != null && data.getString("state") == "completed") {
                FittingNotifications.finished(applicationContext, key, owner)
                return@withContext Result.success()
            }
            val resumed = FittingTaskStore.job(data)
            if (resumed != null) tasks.deletePerson(key)
            val clothes = mutableListOf<TryOnGarment>()
            val person = if (resumed == null) tasks.person(key) else byteArrayOf()
            if (resumed == null) {
                val records = repository.validate(outfit)
                val slots = listOf("top", "bottom", "outerwear")
                check(outfit.items.none { it.slot == "outerwear" } || slots.all { slot -> outfit.items.any { it.slot == slot } }) {
                    "아우터 피팅에는 상의와 하의가 모두 필요해요."
                }
                // The garment photos are independent downloads: fetch them together, keep the slot order.
                val picked = slots.mapNotNull { slot -> outfit.items.firstOrNull { it.slot == slot }?.let { slot to it } }
                // Shoes and accessories are not dressed; an outfit of only those has nothing to fit.
                check(picked.isNotEmpty()) { "이 코디에는 입혀볼 상의나 하의가 없어요." }
                val images = kotlinx.coroutines.coroutineScope {
                    picked.map { (_, item) -> async {
                        WardrobeRepository.fittingImage(records.getValue(item.garmentId).getString("image_url"), account, apiBaseUrl)
                    } }.awaitAll()
                }
                picked.forEachIndexed { index, (slot, item) ->
                    val record = records.getValue(item.garmentId)
                    clothes.add(TryOnGarment(
                        when (slot) { "top" -> "tops"; "bottom" -> "bottoms"; else -> "outerwear" },
                        images[index], if (record.optString("image_source") == "worn_crop") "model" else "flat-lay",
                    ))
                }
            }
            TryOnClient(account, apiBaseUrl).fit(person, clothes, true, resumeJob = resumed,
                onSubmitted = { tasks.submitted(key, it) }, keepForResume = true,
                onProgress = { tasks.progress(key, it) }, onRatingPrompt = { tasks.ratingPrompt(key, it) },
                onResult = { image ->
                    val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: error("피팅 결과를 읽지 못했어요.")
                    bitmap.recycle()
                    check(currentAccount()?.userId == owner) { "계정이 변경되어 피팅을 중단했어요." }
                    repository.saveResult(outfit, image, source)
                    usage?.generated()
                    tasks.finish(key)
                    FittingNotifications.finished(applicationContext, key, owner)
                }) {}
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is java.io.IOException && tasks.read(key)?.let(FittingTaskStore::job) != null) {
                return@withContext Result.retry()
            }
            val message = when {
                error is java.io.IOException -> "피팅 작업을 완료하지 못했어요. 사진을 다시 선택해 주세요."
                // A garment of the outfit was deleted from the wardrobe after the outfit was shown.
                error is com.swpp.stylemate.data.AuthException && error.code in listOf("HTTP_404", "GARMENT_NOT_FOUND") -> GARMENT_GONE_MESSAGE
                else -> error.message ?: "피팅 생성에 실패했어요. 다시 시도해 주세요."
            }
            if (error is FittingApiException && error.code == "FITTING_CANCELLED") usage?.cancel()
            else usage?.failure(usageErrorCode(error))
            tasks.finish(key, message)
            if (currentAccount()?.userId == owner) FittingNotifications.finished(applicationContext, key, owner, failed = true)
            Result.failure()
        }
    }
    private suspend fun currentAccount(): AccountAccess? {
        if (accountProvider != null) return accountProvider.invoke()
        val saved = KeystoreSessionStore(applicationContext)
        // A stale background request must not clear a newer foreground login.
        val readOnly = object : AuthSessionStore {
            override fun read() = saved.read()
            override fun write(value: String) = Unit
            override fun clear() = Unit
        }
        val auth = AuthRepository(BuildConfig.API_BASE_URL, readOnly)
        auth.restore()
        return auth.account()
    }
}
