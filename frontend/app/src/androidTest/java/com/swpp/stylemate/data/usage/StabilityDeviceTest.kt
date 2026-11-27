package com.swpp.stylemate.data.usage

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StabilityDeviceTest {
    @Test fun applicationInstallsCollectorBeforeAnyActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context is StabilityApplication)
        val installation = context.getSharedPreferences("stability", Context.MODE_PRIVATE).getString("installation", null)!!
        assertEquals(installation, UUID.fromString(installation).toString())
    }

    @Test fun heartbeatDetectsBlockedMainLooperAndRecoversAfterItUnblocks() {
        val handler = Handler(Looper.getMainLooper())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val watchdog = MainThreadWatchdog(thresholdMs = 50)
        handler.post { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val ping = watchdog.tick(SystemClock.uptimeMillis(), true).ping!!
            handler.post { watchdog.acknowledge(ping); acknowledged.countDown() }
            SystemClock.sleep(80)
            assertTrue(watchdog.tick(SystemClock.uptimeMillis(), true).stallMs!! >= 50)
            assertTrue(StabilityEvents.frames(Looper.getMainLooper().thread.stackTrace).isNotEmpty())
        } finally { release.countDown() }
        assertTrue(acknowledged.await(2, TimeUnit.SECONDS))
        assertNotNull(watchdog.tick(SystemClock.uptimeMillis(), true).ping)
    }

    @Test fun ownOsExitHistoryIsAvailableWithoutAdditionalPermissions() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rows = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 32)
        assertNotNull(rows)
    }
}
