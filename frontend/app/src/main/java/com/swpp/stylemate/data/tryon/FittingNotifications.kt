package com.swpp.stylemate.data.tryon

import android.app.Notification
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.swpp.stylemate.MainActivity
import com.swpp.stylemate.data.LocalUserStore

object FittingNotifications {
    const val EXTRA_TASK = "fitting_task"
    private const val CHANNEL = "fitting-results"
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun notification(context: Context, key: String, completed: Boolean, failed: Boolean = false): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "가상 피팅", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_TASK, key)
            .setAction("com.swpp.stylemate.FITTING.$key")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(when { failed -> "가상 피팅을 생성하지 못했어요"; completed -> "가상 피팅 생성 완료!"; else -> "가상 피팅을 만드는 중입니다" })
            .setContentText(if (completed) "눌러서 피팅 결과를 확인하세요." else if (failed) "코디에서 다시 시도해 주세요." else "완료되면 알려드릴게요.")
            .setContentIntent(pending).setAutoCancel(completed || failed).setOngoing(!completed && !failed)
            .setOnlyAlertOnce(true).setSilent(!completed && !failed).build()
    }
    fun finished(context: Context, key: String, owner: String, failed: Boolean = false) {
        val enabled = try {
            val store = LocalUserStore.forContext(context, owner)
            FittingNotificationSettings.decode(store.get(FittingNotificationSettings.storageKey(owner))).enabled
        } catch (_: Exception) { false }
        if (!enabled) return
        if (!allowed(context)) return
        try { NotificationManagerCompat.from(context).notify(key.hashCode(), notification(context, key, !failed, failed)) }
        catch (_: SecurityException) { /* Results remain accessible from the outfit without notification permission. */ }
    }
}
