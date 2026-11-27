package com.swpp.stylemate.home

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.MainActivity
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/** Opt-in device test against Django testserver and generated garment photos, without API interceptors. */
class PinnedOutfitLiveTest {
    companion object {
        @BeforeClass @JvmStatic fun requireIsolatedServer() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Requires an explicitly configured isolated Django testserver",
                args.getString("livePinnedOutfits") == "true" &&
                    args.getString("liveApiBaseUrl") == BuildConfig.API_BASE_URL &&
                    BuildConfig.API_BASE_URL.startsWith("http://10.0.2.2:"))
        }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(350, 3_000)
        val bitmap = automation.takeScreenshot()
        assertNotNull(bitmap)
        val directory = File(compose.activity.getExternalFilesDir(null), "pinned-live").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForHome() {
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("새로 추천") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("코디 3 선택").assertExists()
        waitForPhotos()
    }

    private fun waitForPhotos() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("사진 준비 중", substring = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("사진을 불러오지 못했어요", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("사진을 불러오지 못했습니다", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("상세에서", substring = true).assertCountEquals(0)
    }

    private fun openTab(label: String) {
        compose.onNode(hasText(label) and isSelectable()).performClick()
    }

    private fun openGarment(category: String, name: String) {
        openTab("옷장")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("옷걸이를 좌우로 넘기는 옷장").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(category) and isSelectable()).performClick()
        val selected = hasContentDescription(name) and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        compose.waitUntil(30_000) { compose.onAllNodes(selected).fetchSemanticsNodes().isNotEmpty() }
        waitForPhotos()
        compose.onNode(selected).assertIsDisplayed().performClick()
        compose.onNodeWithText("이 옷을 포함한 코디 추천받기").assertIsDisplayed()
    }

    @Test fun realPhotosRenderAndAllThreeGarmentCategoriesStayPinnedAcrossRecommendations() {
        val args = InstrumentationRegistry.getArguments()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("아이디").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("아이디").performTextInput(args.getString("liveUsername")!!)
        compose.onNodeWithText("비밀번호").performTextInput(args.getString("livePassword")!!)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("로그인").performClick()
        waitForHome()
        capture("01-default-outfits")

        val garments = listOf("상의" to "베이지 케이블 니트", "하의" to "카키 치노", "아우터" to "네이비 블레이저")
        garments.forEachIndexed { index, (category, name) ->
            openGarment(category, name)
            capture("${index + 2}-selection-$index")
            compose.onNodeWithText("이 옷을 포함한 코디 추천받기").performClick()
            waitForHome()
            compose.onNode(hasText("홈") and isSelectable()).assertIsSelected()
            compose.onNodeWithText("고정한 옷").assertIsDisplayed()
            compose.onNodeWithText(name).assertIsDisplayed()
            repeat(3) { page ->
                compose.onNodeWithContentDescription("코디 ${page + 1} 선택").performClick()
                waitForPhotos()
                compose.onAllNodes(hasContentDescription(name) and
                    hasAnyAncestor(hasContentDescription("코디 ${page + 1} / 3")))
                    .onFirst().assertIsDisplayed()
                capture("pinned-$index-outfit-${page + 1}")
            }

            compose.onNodeWithText("새로 추천").performScrollTo().performClick()
            waitForHome()
            compose.onNodeWithText("고정한 옷").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(name).assertIsDisplayed()
            capture("refresh-keeps-pin-$index")

            if (index == 0) {
                compose.onNodeWithContentDescription("코디북에 저장").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithContentDescription("코디북에 저장됨").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("좋아요").performScrollTo().performClick()
                openTab("코디북")
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithContentDescription("저장한 코디 1 / 1").fetchSemanticsNodes().isNotEmpty()
                }
                waitForPhotos()
                capture("saved-outfit-with-real-photos")
                openTab("홈")
                compose.onNodeWithText("고정한 옷").performScrollTo().assertIsDisplayed()
            }
            openTab("옷장")
            openTab("홈")
            compose.onNodeWithText("고정한 옷").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("고정 해제").performClick()
            waitForHome()
            compose.onNodeWithText("고정한 옷").assertDoesNotExist()
            capture("unpin-restores-default-$index")
        }

        openGarment("상의", "베이지 케이블 니트")
        compose.onNodeWithText("함께 입을 옷 찾기").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("내 옷장에서 함께 입기").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("garment-match-list")
            .performScrollToNode(hasText("무신사에서 더 알아보기 ↗"))
        compose.onNodeWithText("무신사에서 더 알아보기 ↗").assertIsDisplayed().assertHasClickAction()
        capture("matching-clothes-search")
    }
}
