package com.swpp.stylemate.data.usage

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.work.*
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.AuthSessionStore
import com.swpp.stylemate.data.KeystoreSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object UsageMetrics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val trackers = mutableMapOf<String, UsageTracker>()

    fun forAccount(context: Context, owner: String): UsageTracker = synchronized(trackers) {
        require(UUID.fromString(owner).toString() == owner)
        val application = context.applicationContext
        trackers.getOrPut(owner) {
            UsageTracker(emit = { event -> scope.launch {
                runCatching { queue(application, owner).add(event); schedule(application, owner) }
            } }, elapsed = SystemClock::elapsedRealtime,
                boot = { runCatching { Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1) },
                version = BuildConfig.VERSION_NAME)
        }
    }

    /** 측정 이벤트가 아닌 이벤트(별점 등)를 같은 큐로 보낸다. 오프라인이면 연결 후 재전송된다. */
    fun enqueue(context: Context, owner: String, event: JSONObject) {
        require(UUID.fromString(owner).toString() == owner)
        val application = context.applicationContext
        scope.launch { runCatching { queue(application, owner).add(event); schedule(application, owner) } }
    }

    internal fun queue(context: Context, owner: String): UsageQueue {
        require(UUID.fromString(owner).toString() == owner)
        return UsageQueue(File(context.noBackupFilesDir, "usage/$owner.json"))
    }

    fun schedule(context: Context, owner: String) {
        val work = OneTimeWorkRequestBuilder<UsageUploadWorker>().setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // APPEND_OR_REPLACE closes the race where a new event arrives just as an upload is finishing.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork("usage:$owner", ExistingWorkPolicy.APPEND_OR_REPLACE, work) }
    }
}

class UsageUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        return try {
            val saved = KeystoreSessionStore(applicationContext)
            val readOnly = object : AuthSessionStore {
                override fun read() = saved.read()
                override fun write(value: String) = Unit
                override fun clear() = Unit
            }
            val auth = AuthRepository(BuildConfig.API_BASE_URL, readOnly)
            auth.restore()
            val account = auth.account() ?: return Result.success()
            if (account.userId != owner) return Result.success()
            upload(UsageMetrics.queue(applicationContext, owner), account)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        internal suspend fun upload(queue: UsageQueue, account: AccountAccess) {
            while (true) {
                val batch = queue.batch()
                if (batch.isEmpty()) return
                val response = account.json("POST", "/api/usage/events/", JSONObject().put("events", JSONArray(batch)))
                val accepted = response.getJSONArray("accepted")
                val ids = (0 until accepted.length()).map { accepted.getString(it) }.toSet()
                check(batch.all { it.getString("id") in ids })
                queue.acknowledge(batch.map { it.getString("id") }.toSet())
            }
        }
    }
}
