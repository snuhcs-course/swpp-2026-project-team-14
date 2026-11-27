// AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
package com.swpp.stylemate.wardrobe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.WardrobeCarousel
import com.swpp.stylemate.ui.wardrobe.WardrobeCollection
import com.swpp.stylemate.ui.wardrobe.WardrobeItem
import com.swpp.stylemate.ui.wardrobe.HangingGarmentImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI contracts from the redesign brief. No account, server, paid inference or real user photos. */
class WardrobeRailTest {
    @get:Rule val compose = createComposeRule()
    private val railDescription = "옷걸이를 좌우로 넘기는 옷장"
    private val railItem = SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionItemInfo)
    private val selectable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun fixtures(count: Int): List<WardrobeItem> {
        // Small transparent image fixture shared by every record; never added to app data.
        val thumbnail = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawRect(9f, 8f, 39f, 58f, Paint().apply { color = 0xFF556677.toInt() })
        }.asImageBitmap()
        return List(count) { index ->
            WardrobeItem("rail-$index", "테스트 의류 $index", if (index % 3 == 1) "bottom" else "top",
                thumbnail = thumbnail)
        }
    }

    @Test fun shoePhotoWithAnOrdinaryBackgroundRemainsVisible() {
        // An opaque brown photo fails the studio matte. Unsupported categories must show it intact.
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFAA7733.toInt()) }
        val item = WardrobeItem("saved-shoe", "원본 신발 사진", "shoes",
            imageUrl = "/api/wardrobe/items/saved-shoe/image/", thumbnail = bitmap.asImageBitmap())
        try {
            compose.setContent { StyleMateTheme {
                HangingGarmentImage(item, Modifier.fillMaxWidth().height(300.dp))
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(item.name).fetchSemanticsNodes().isNotEmpty()
            }
            val pixels = compose.onNodeWithContentDescription(item.name).captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertEquals(0xAA / 255f, center.red, .02f)
            assertEquals(0x77 / 255f, center.green, .02f)
            assertEquals(0x33 / 255f, center.blue, .02f)
        } finally { bitmap.recycle() }
    }

    @Composable
    private fun Collection(items: List<WardrobeItem>, register: () -> Unit = {}) {
        var category by remember { mutableStateOf("all") }
        WardrobeCollection(
            total = items.size, items = items.filter { category == "all" || it.category == category },
            allItems = items, categories = listOf("all" to "전체", "top" to "상의", "bottom" to "하의"),
            category = category, onCategory = { category = it }, message = null, loading = false,
            cameraBusy = false, onRegister = register, onRetry = null, onItemClick = {},
            onDelete = null, deletingId = null, photo = {},
        )
    }

    @Test fun emptyThenOneRecordNeverInventsClothesAndKeepsRegistrationAvailable() {
        val records = mutableStateOf(emptyList<WardrobeItem>())
        var registrations = 0
        compose.setContent { StyleMateTheme { Collection(records.value) { registrations++ } } }
        compose.onNodeWithText("총 0벌").assertExists()
        compose.onNodeWithText("첫 번째 옷을 걸어보세요").assertExists()
        compose.onAllNodes(railItem).assertCountEquals(0)
        compose.onNodeWithText("등록하기").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, registrations); records.value = fixtures(1) }
        compose.onNodeWithText("총 1벌").assertExists()
        compose.onAllNodes(railItem).assertCountEquals(1)
        compose.onNodeWithContentDescription(railDescription)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 / 1"))
        compose.onNodeWithContentDescription(railDescription).performTouchInput { swipeLeft() }
        compose.onAllNodes(railItem).assertCountEquals(1)
        compose.onNode(railItem).assertIsSelected()
    }

    @Test fun hundredRecordsRenderOnlyNearbyItemsAndSwipeChangesSelection() {
        val items = fixtures(100)
        var selected: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeCarousel(items, items.first().id, { selected = it }, {},
                modifier = Modifier.fillMaxWidth().height(328.dp))
        } }
        compose.runOnIdle { assertEquals(items.first().id, selected) }
        assertTrue(compose.onAllNodes(railItem).fetchSemanticsNodes().size in 1..11)
        compose.onNodeWithContentDescription(railDescription).performTouchInput { swipeLeft(durationMillis = 450) }
        compose.waitForIdle()
        compose.runOnIdle { assertNotEquals(items.first().id, selected) }
        assertTrue(compose.onAllNodes(railItem).fetchSemanticsNodes().size in 1..11)
        compose.onAllNodes(railItem and isSelected()).assertCountEquals(1)
        compose.onNode(railItem and hasContentDescription(items.last().name)).assertDoesNotExist()
    }

    @Test fun sideItemCentersBeforeSecondTapOpensItsDetail() {
        val items = fixtures(3)
        var opened: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeCarousel(items, items.first().id, {}, { opened = it.id },
                modifier = Modifier.fillMaxWidth().height(328.dp))
        } }
        val second = compose.onNode(railItem and hasContentDescription(items[1].name))
        compose.waitUntil(3_000) { compose.onAllNodesWithText("사진 준비 중").fetchSemanticsNodes().isEmpty() }
        // A rotated item's unprojected semantic bounds can extend beyond the display.
        // Tap the exposed right-hand garment inside the actual rail viewport.
        compose.onNodeWithContentDescription(railDescription).performTouchInput {
            click(Offset(width * .82f, height * .50f))
        }
        compose.waitForIdle()
        second.assertIsSelected()
        compose.runOnIdle { assertNull(opened) }
        second.performClick()
        compose.runOnIdle { assertEquals(items[1].id, opened) }
    }

    @Test fun accessibilityClickCentersSideItemBeforeOpeningDetail() {
        val items = fixtures(3)
        var opened: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeCarousel(items, items.first().id, {}, { opened = it.id },
                modifier = Modifier.fillMaxWidth().height(328.dp))
        } }
        val second = compose.onNode(railItem and hasContentDescription(items[1].name))
        second.performSemanticsAction(SemanticsActions.OnClick)
        second.assertIsSelected()
        compose.runOnIdle { assertNull(opened) }
        second.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(items[1].id, opened) }
    }

    @Test fun searchCanShowNoMatchesAndClearingRestoresActualRecords() {
        val items = fixtures(3)
        compose.setContent { StyleMateTheme { Collection(items) } }
        compose.onNodeWithContentDescription("옷 검색").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("없는 옷 이름")
        compose.onNodeWithText("검색 결과가 없어요").assertExists()
        compose.onAllNodes(railItem).assertCountEquals(0)
        compose.onNodeWithText("총 3벌").assertExists()
        compose.onNodeWithContentDescription("검색어 지우기").performClick()
        compose.onAllNodes(railItem).assertCountEquals(3)
        compose.onNodeWithContentDescription("검색 닫기").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun overviewCountsRemainGlobalAndGridReturnsToRail() {
        val items = fixtures(3) // two tops, one bottom
        compose.setContent { StyleMateTheme { Collection(items) } }
        compose.onNode(hasText("하의") and selectable).performClick().assertIsSelected()
        compose.onAllNodes(railItem).assertCountEquals(1)
        compose.onNodeWithText("총 3벌").assertExists()
        // Overview counts must describe all account records, not only the active filter.
        compose.onNode(hasText("상의") and hasText("2벌")).performScrollTo().performClick()
        compose.onAllNodes(railItem).assertCountEquals(2)
        compose.onNodeWithText("전체 보기").performScrollTo().performClick()
        compose.onNodeWithText("전체 옷 보기").assertIsDisplayed()
        compose.onNodeWithContentDescription("옷장봉으로 돌아가기").performClick()
        compose.onNodeWithContentDescription(railDescription).assertExists()
        compose.onAllNodes(railItem).assertCountEquals(3)
    }
}
