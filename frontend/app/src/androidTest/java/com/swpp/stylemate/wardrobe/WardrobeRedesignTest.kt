// AI-assisted wardrobe visual/accessibility regression checks, 2026-11-18.
package com.swpp.stylemate.wardrobe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.WardrobeItem
import com.swpp.stylemate.ui.wardrobe.WardrobeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic garments only. Renders the service composable without accounts, APIs or file cleanup. */
class WardrobeRedesignTest {
    @get:Rule val compose = createComposeRule()

    private fun garment(width: Int, height: Int, color: Int, trousers: Boolean = false) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.scale(width / 200f, height / 240f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            val points = if (trousers) listOf(55f to 20f, 145f to 20f, 155f to 220f,
                110f to 220f, 100f to 100f, 90f to 220f, 45f to 220f)
            else listOf(65f to 35f, 82f to 25f, 118f to 25f, 135f to 35f,
                180f to 75f, 152f to 105f, 135f to 90f, 135f to 215f,
                65f to 215f, 65f to 90f, 48f to 105f, 20f to 75f)
            val path = Path().apply {
                moveTo(points.first().first, points.first().second)
                points.drop(1).forEach { lineTo(it.first, it.second) }
                close()
            }
            canvas.drawPath(path, paint)
            paint.color = 0x30000000
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            canvas.drawPath(path, paint)
        }.asImageBitmap()

    private fun fixtures() = listOf(
        WardrobeItem("shirt", "코튼 오버핏 셔츠", "top", "미니멀", garment(360, 440, 0xFFE4E1DA.toInt())),
        WardrobeItem("denim", "인디고 스트레이트 데님 팬츠", "bottom", "캐주얼", garment(240, 640, 0xFF394D66.toInt(), true)),
        WardrobeItem("knit", "소프트 울 라운드 니트", "top", "캐주얼", garment(640, 300, 0xFFAAA294.toInt())),
        WardrobeItem("jacket", "가을부터 초겨울까지 입는 아주 긴 이름의 울 블렌드 재킷", "outerwear", "클래식", garment(360, 480, 0xFF424341.toInt())),
        WardrobeItem("trousers", "와이드 코튼 팬츠", "bottom", "미니멀", garment(240, 640, 0xFFCCBDA7.toInt(), true)),
        WardrobeItem("shoes", "화이트 스니커즈", "shoes"),
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val prefix = InstrumentationRegistry.getArguments().getString("wardrobeCapturePrefix") ?: "after"
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "wardrobe-redesign")
        directory.mkdirs()
        File(directory, "$prefix-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun captureReferenceWardrobe() {
        val items = fixtures()
        compose.setContent { StyleMateTheme { WardrobeScreen(items, null, false, {}, onDelete = {}) } }
        compose.onNodeWithText("총 6벌").assertIsDisplayed()
        capture("01-grid")
    }

    @Test fun loadingDoesNotClaimTheWardrobeIsEmpty() {
        compose.setContent { StyleMateTheme { WardrobeScreen(emptyList(), null, false, {}, loading = true) } }
        compose.onNodeWithText("아직 등록된 옷이 없습니다").assertDoesNotExist()
        compose.onNodeWithText("옷장을 불러오고 있어요").assertIsDisplayed()
        capture("02-loading")
    }

    @Test fun initialErrorOffersRetryWithoutClaimingAnEmptyWardrobe() {
        var retried = 0
        compose.setContent { StyleMateTheme {
            WardrobeScreen(emptyList(), "네트워크 연결을 확인해 주세요.", false, {}, onRetry = { retried++ })
        } }
        compose.onNodeWithText("아직 등록된 옷이 없습니다").assertDoesNotExist()
        compose.onNodeWithText("네트워크 연결을 확인해 주세요.").assertIsDisplayed()
        capture("03-error")
        compose.onNodeWithText("다시 불러오기").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun failedRefreshKeepsClothesAndRetryIsDisabledDuringLoading() {
        val loading = mutableStateOf(false)
        compose.setContent { StyleMateTheme {
            WardrobeScreen(fixtures(), "연결이 끊겼어요. 다시 시도해 주세요.", false, {},
                loading = loading.value, onRetry = {})
        } }
        compose.onNodeWithText("코튼 오버핏 셔츠").assertIsDisplayed()
        compose.onNodeWithText("다시 불러오기").assertIsEnabled()
        capture("04-cached-error")
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("다시 불러오기").assertIsNotEnabled()
    }

    @Test fun filtersExposeSelectionAndMinimumTouchTarget() {
        compose.setContent { StyleMateTheme { WardrobeScreen(fixtures(), null, false, {}) } }
        compose.onNodeWithText("상의").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.onNodeWithText("전체").assertIsNotSelected()
        compose.onNodeWithText("코튼 오버핏 셔츠").assertIsDisplayed()
        compose.onNodeWithText("인디고 스트레이트 데님 팬츠").assertDoesNotExist()
        compose.onNodeWithText("총 6벌").assertIsDisplayed()
        capture("05-filter")
    }

    @Test fun emptyWardrobeKeepsRegistrationAvailable() {
        compose.setContent { StyleMateTheme { WardrobeScreen(emptyList(), null, false, {}) } }
        compose.onNodeWithText("아직 등록된 옷이 없습니다").assertIsDisplayed()
        compose.onNodeWithText("등록하기").assertIsDisplayed().assertIsEnabled()
        capture("06-empty")
    }

    @Test fun smallScreenKeepsTwoColumnsAndMissingPhotoIsReachable() {
        val items = fixtures()
        compose.setContent { StyleMateTheme {
            Box(Modifier.size(width = 320.dp, height = 568.dp)) { WardrobeScreen(items, null, false, {}, onDelete = {}) }
        } }
        val shirt = compose.onNodeWithText(items[0].name).fetchSemanticsNode().boundsInRoot
        val denim = compose.onNodeWithText(items[1].name).fetchSemanticsNode().boundsInRoot
        assertEquals(shirt.top, denim.top, 1f)
        compose.onNodeWithContentDescription("${items[0].name} 삭제").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("등록하기").assertIsDisplayed()
        capture("09-small")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(items.last().name))
        compose.onNodeWithText("사진 없음").assertIsDisplayed()
    }

    @Test fun smallScreenAndLargeFontKeepActionsAndLongNamesReachable() {
        val items = fixtures()
        compose.setContent { StyleMateTheme {
            val configuration = Configuration(LocalConfiguration.current).apply { screenWidthDp = 320; fontScale = 2f }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(width = 320.dp, height = 568.dp)) { WardrobeScreen(items, null, false, {}, onDelete = {}) }
            }
        } }
        compose.onNodeWithText("등록하기").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("옷장").assertIsDisplayed()
        capture("07-small-large-font")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(items[3].name))
        compose.onNodeWithText(items[3].name).assertIsDisplayed()
        compose.onNodeWithContentDescription("${items[3].name} 삭제").assertIsDisplayed()
        capture("08-long-name")
    }
}
