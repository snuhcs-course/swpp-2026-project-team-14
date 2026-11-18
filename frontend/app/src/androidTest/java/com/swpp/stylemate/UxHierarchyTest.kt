package com.swpp.stylemate

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.recommendation.*
import com.swpp.stylemate.ui.chat.*
import com.swpp.stylemate.ui.home.*
import com.swpp.stylemate.ui.profile.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.theme.enableStyleMateEdgeToEdge
import com.swpp.stylemate.ui.wardrobe.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UxHierarchyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val outfit = Outfit("o1", "a+b", 78, listOf(
        OutfitGarment("a", "top", "코튼 셔츠", "", ""), OutfitGarment("b", "bottom", "데님 팬츠", "", "")),
        listOf(Reason("color", "차분한 색상끼리 함께 골랐어요.", ""), Reason("fit", "선호하는 넉넉한 핏이에요.", "")), true, "데일리 코디", null, "")
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { StyleMateTheme { Box(Modifier.width(360.dp).height(700.dp)) { content() } } }
    }
    private fun capture(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ux-hierarchy").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.waitForIdle()
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun homeRatingStaysBelowThePhotoAndReasonsKeepTheirHierarchy() {
        show { HomeScreen(HomeState(outfits = listOf(outfit)), {}, {}, {}, {}, {}, {}, {},
            photo = { _, m -> Box(m.background(Color(0xFFE8E5E0))) }) }
        val photo = compose.onNodeWithContentDescription("코디 1 / 1").fetchSemanticsNode().boundsInRoot
        val rating = compose.onNodeWithContentDescription("추천 별점", substring = true).assertHasNoClickAction().fetchSemanticsNode().boundsInRoot
        assertTrue(rating.top >= photo.bottom)
        compose.onNodeWithText("이 코디를 고른 이유").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("색상").assertIsDisplayed()
        capture("home")
    }
}
