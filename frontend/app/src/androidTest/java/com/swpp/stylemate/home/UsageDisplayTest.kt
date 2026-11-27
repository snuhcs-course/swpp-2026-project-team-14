package com.swpp.stylemate.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.tryon.PhotoFittingContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UsageDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fittingCompletesOnlyAfterImageDrawAndDoesNotRepeatOnRecomposition() {
        val pending = mutableStateOf(true)
        val saving = mutableStateOf(false)
        val count = AtomicInteger()
        val image = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888)
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, image, null, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                pending = pending.value, saving = saving.value, onResultDisplayed = { count.incrementAndGet() })
        } }
        compose.runOnIdle { assertEquals(0, count.get()); pending.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, count.get()); saving.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, count.get()) }
    }

    @Test fun revealedResultsAreRecordedOncePerImageAndNeverWhilePending() {
        val pending = mutableStateOf(true)
        val saving = mutableStateOf(false)
        val image = mutableStateOf(Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888))
        val count = AtomicInteger()
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, image.value, null, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                pending = pending.value, revealing = true, saving = saving.value,
                onResultDisplayed = { count.incrementAndGet() })
        } }
        compose.runOnIdle { assertEquals(0, count.get()); pending.value = false }
        compose.waitUntil(5_000) { count.get() == 1 }
        compose.onNodeWithContentDescription("생성된 피팅 이미지").assertIsDisplayed()
        compose.runOnIdle { saving.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, count.get())
            pending.value = true
            image.value = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, count.get()); pending.value = false }
        compose.waitUntil(5_000) { count.get() == 2 }
        compose.onNodeWithContentDescription("생성된 피팅 이미지").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, count.get()) }
    }
}
