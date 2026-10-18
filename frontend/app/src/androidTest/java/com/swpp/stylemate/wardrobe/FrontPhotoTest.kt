// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.wardrobe

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.FrontPhotoCard
import com.swpp.stylemate.ui.wardrobe.needsFrontPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FrontPhotoTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyTopsAndOuterwearNeedAFrontPhoto() {
        assertTrue(needsFrontPhoto("top"))
        assertTrue(needsFrontPhoto("outerwear"))
        assertFalse(needsFrontPhoto("bottom"))
        assertFalse(needsFrontPhoto(null))
    }

    @Test fun cardOffersCameraAndGallery() {
        val events = mutableListOf<String>()
        compose.setContent { StyleMateTheme { Column {
            FrontPhotoCard(null, loading = false, enabled = true, onCamera = { events += "camera" }, onGallery = { events += "gallery" })
        } } }
        compose.onNodeWithText("아직 앞면 사진이 없어요").assertIsDisplayed()
        compose.onNodeWithText("앞면 촬영").performClick()
        compose.onNodeWithText("갤러리에서 선택").performClick()
        compose.runOnIdle { assertEquals(listOf("camera", "gallery"), events) }
    }

    @Test fun buttonsAreDisabledWhileAnalysing() {
        compose.setContent { StyleMateTheme { Column {
            FrontPhotoCard(null, loading = false, enabled = false, onCamera = {}, onGallery = {})
        } } }
        compose.onNodeWithText("앞면 촬영").assertIsNotEnabled()
        compose.onNodeWithText("갤러리에서 선택").assertIsNotEnabled()
    }
}
