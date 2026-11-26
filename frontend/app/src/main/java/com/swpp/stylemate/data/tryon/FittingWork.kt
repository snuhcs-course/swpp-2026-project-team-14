package com.swpp.stylemate.data.tryon

import android.content.Context
import androidx.work.*
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.AuthSessionStore
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.KeystoreSessionStore
import com.swpp.stylemate.data.LocalUserStore
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
            tasks.create(key, owner, source, outfit, person())
            val work = OneTimeWorkRequestBuilder<FittingWorker>().setInputData(workDataOf("key" to key))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            try { manager.enqueueUniqueWork("fitting:$key", ExistingWorkPolicy.KEEP, work).result.get() }
            catch (error: Exception) { tasks.finish(key, "작업을 시작하지 못했어요. 다시 시도해 주세요."); throw error }
            true
        }
    }
    suspend fun state(key: String): JSONObject? = withContext(Dispatchers.IO) {
        enqueueLock.withLock {
            val data = tasks.read(key) ?: return@withLock null
            if (data.optString("state") in listOf("queued", "running")) {
                val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork("fitting:$key").get().any { !it.state.isFinished }
                if (!active && tasks.read(key)?.optString("state") in listOf("queued", "running")) {
                    tasks.finish(key, "피팅 작업이 중단됐어요. 사진을 다시 선택해 주세요.")
                }
            } else tasks.deletePerson(key)
            tasks.read(key)
        }
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
                onProgress = { tasks.progress(key, it) },
                onResult = { image ->
                    val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: error("피팅 결과를 읽지 못했어요.")
                    bitmap.recycle()
                    check(currentAccount()?.userId == owner) { "계정이 변경되어 피팅을 중단했어요." }
                    repository.saveResult(outfit, image, source)
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
            val message = if (error is java.io.IOException) "피팅 작업을 완료하지 못했어요. 사진을 다시 선택해 주세요."
                else error.message ?: "피팅 생성에 실패했어요. 다시 시도해 주세요."
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
