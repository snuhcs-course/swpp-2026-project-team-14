// AI-generated with Codex, 2026-10-04, reviewed by Hyeon U Jeong
package com.swpp.stylemate.wardrobe

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WardrobeColorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fitCoordinatesRejectPaddingAndSampleTheVisiblePixel() {
        val wide = IntSize(200, 100)
        assertNull(photoPixelAt(Offset(100f, 10f), IntSize(200, 200), wide.width, wide.height))
        assertEquals(IntOffset(100, 0), photoPixelAt(Offset(100f, 50f), IntSize(200, 200), 200, 100))
        assertNull(photoPixelAt(Offset(100f, 150f), IntSize(200, 200), 200, 100))
        assertNull(photoPixelAt(Offset(49f, 100f), IntSize(200, 200), 100, 200))
        assertEquals(IntOffset(0, 100), photoPixelAt(Offset(50f, 100f), IntSize(200, 200), 100, 200))
        assertEquals(IntOffset(99, 99), photoPixelAt(Offset(199f, 199f), IntSize(200, 200), 100, 100))
        assertNull(photoPixelAt(Offset.Zero, IntSize.Zero, 100, 100))
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, 0xFF123456.toInt()); setPixel(1, 0, 0xFFABCDEF.toInt())
        }.asImageBitmap()
        assertEquals("#123456", bitmap.colorAt(IntOffset(0, 0)))
        assertEquals("#ABCDEF", bitmap.colorAt(IntOffset(1, 0)))
    }

    @Test fun paletteAndPhotoColorsSaveWhileCancelAndDuplicateLeaveColorsUnchanged() {
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("wardrobe-editor.json").bufferedReader().use { it.readText() })
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF2468AC.toInt())
        }.asImageBitmap()
        var submitted: JSONObject? = null
        compose.setContent { StyleMateTheme {
            GarmentEditor("colors", fixture.getJSONObject("attributes"), null, "", fixture.getJSONObject("catalog"),
                false, false, null, { submitted = it }, image = bitmap)
        } }
        compose.onNodeWithText("색상표").performScrollTo().performClick()
        screenshot("palette")
        compose.onNodeWithContentDescription("빨강 선택").performClick()
        compose.onNodeWithContentDescription("빨강 색상 삭제").assertIsDisplayed()
        compose.onNodeWithContentDescription("사진에서 색상 추가").performClick()
        compose.onNodeWithTag("color-photo").performTouchInput { click(Offset(center.x, 1f)) }
        compose.onNodeWithText("추가").assertIsNotEnabled()
        compose.onNodeWithTag("color-photo").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("선택한 색상 #2468AC").assertExists()
        screenshot("photo-color")
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithContentDescription("#2468AC 색상 삭제").assertDoesNotExist()
        compose.onNodeWithContentDescription("사진에서 색상 추가").performClick()
        compose.onNodeWithTag("color-photo").performTouchInput { click(center) }
        compose.onNodeWithText("추가").performClick()
        compose.onNodeWithContentDescription("사진에서 색상 추가").performClick()
        compose.onNodeWithTag("color-photo").performTouchInput { click(center) }
        compose.onNodeWithText("이미 추가한 색상").assertIsDisplayed()
        compose.onNodeWithText("추가").assertIsNotEnabled()
        compose.onNodeWithText("취소").performClick()
        screenshot("editor-colors")
        compose.onNodeWithText("옷장에 추가").performClick()
        compose.runOnIdle {
            val colors = requireNotNull(submitted).getJSONObject("attributes").getJSONArray("colors")
            assertEquals(listOf("#FFFFFF", "#C83C3C", "#2468AC"), (0 until colors.length()).map { colors.getString(it) })
        }
        compose.onNodeWithContentDescription("빨강 색상 삭제").performClick()
        compose.onNodeWithContentDescription("빨강 색상 삭제").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
            compose.onNode(isDialog()) else compose.onRoot()
        File(target.cacheDir, "$name.png").outputStream().use {
            root.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
