package com.swpp.stylemate.wardrobe

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.recommendation.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GarmentDetailFlowTest {
    @get:Rule val compose = createComposeRule()
    private val events = mutableListOf<String>()
    private var submitted: JSONObject? = null
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("wardrobe-editor.json").bufferedReader().use { it.readText() })
    private fun record(): JSONObject = JSONObject().put("id", "shirt").put("image_url", "")
        .put("image_source", "ai_flatlay").put("notes", "찬물 세탁")
        .put("attributes", fixture().getJSONObject("attributes").put("name", "여러 계절에 함께 입는 아주 긴 이름의 코튼 오버핏 셔츠"))
        .put("labels", JSONObject().put("fit", "루즈").put("length", "노말"))
        .put("estimated_dimensions", JSONObject().put("category", "top").put("subcategory", "tshirt")
            .put("fit", "루즈").put("length", "노말").put("measurements", JSONObject().put("chest_width_half",
                JSONObject().put("value", 48).put("min", 43).put("max", 45))))

    @Composable private fun SamplePhoto(modifier: Modifier = Modifier) {
        Canvas(modifier.fillMaxSize().background(Color(0xFFF5F5F5)).padding(12.dp)) {
            val x = size.width; val y = size.height
            val shirt = Path().apply {
                moveTo(x*.3f, y*.1f); lineTo(x*.42f, y*.05f); lineTo(x*.58f, y*.05f)
                lineTo(x*.7f, y*.1f); lineTo(x*.95f, y*.33f); lineTo(x*.78f, y*.5f)
                lineTo(x*.7f, y*.4f); lineTo(x*.7f, y*.95f); lineTo(x*.3f, y*.95f)
                lineTo(x*.3f, y*.4f); lineTo(x*.22f, y*.5f); lineTo(x*.05f, y*.33f); close()
            }
            drawPath(shirt, Color(0xFFB9B2A5))
        }
    }

    private fun showDetail(fontScale: Float = 1f) {
        val record = record()
        val catalog = fixture().getJSONObject("catalog")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                StyleMateTheme { Box(Modifier.width(320.dp).height(620.dp)) {
                    var editing by remember { mutableStateOf(false) }
                    if (!editing) GarmentDetailScreen(record, catalog, onBack = {},
                        onEdit = { events += "edit"; editing = true }, onMatch = { events += "match" },
                        onDelete = { events += "delete-request" }, photo = { SamplePhoto() })
                    else GarmentEditor("shirt", record.getJSONObject("attributes"), null, record.getString("notes"), catalog,
                        busy = false, saved = true, error = null, onSave = { submitted = it; editing = false },
                        onBack = { editing = false }, photo = { SamplePhoto() }, measurementsEnabled = false,
                        photoSource = "ai_flatlay", estimatedDimensions = record.getJSONObject("estimated_dimensions"),
                        labels = { _, _ -> GarmentSection("옷 특징") { Text("핏: 루즈"); Text("기장: 보통") } })
                } }
            }
        }
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "garment-detail").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun editAndDeleteShareTheMenuWhileMatchingRemainsThePrimaryAction() {
        showDetail()
        compose.onNodeWithText("정보 수정").assertDoesNotExist()
        compose.onNodeWithText("삭제").assertDoesNotExist()
        compose.onNodeWithText("함께 입을 옷 찾기").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("옷 관리 메뉴").performClick()
        val edit = compose.onNodeWithText("정보 수정").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithText("삭제").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertEquals(edit.left, delete.left, 1f)
        assertEquals(edit.width, delete.width, 1f)
        compose.onNodeWithText("삭제").performClick()
        compose.runOnIdle { assertEquals(listOf("match", "delete-request"), events) }
    }

    @Test fun readerAndEditorSharePhotoSizeAndSectionOrderWithoutChangingTheSavePayload() {
        showDetail()
        val before = compose.onNodeWithTag("garment-photo").getUnclippedBoundsInRoot()
        capture("detail")
        compose.onNodeWithContentDescription("옷 관리 메뉴").performClick()
        compose.onNodeWithText("정보 수정").performClick()
        val after = compose.onNodeWithTag("garment-photo").getUnclippedBoundsInRoot()
        assertEquals(before.right - before.left, after.right - after.left)
        assertEquals(before.bottom - before.top, after.bottom - after.top)
        val sections = listOf("기본 정보", "옷 특징", "추정 치수", "메모").map {
            compose.onNodeWithTag("garment-section-$it").getUnclippedBoundsInRoot().top.value
        }
        assertTrue(sections.zipWithNext().all { (a, b) -> a < b })
        capture("editor")
        compose.onNodeWithText("이름").performScrollTo().performTextReplacement("수정한 셔츠")
        compose.onNodeWithContentDescription("메모").performScrollTo().performTextReplacement("새 메모")
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle {
            val payload = requireNotNull(submitted)
            assertEquals("수정한 셔츠", payload.getJSONObject("attributes").getString("name"))
            assertEquals("새 메모", payload.getString("notes"))
            assertTrue(payload.isNull("dimensions"))
            assertFalse(payload.has("estimated_dimensions"))
        }
    }

    @Test fun estimatesUseOneRangeAndTheShortPhotoLabelRetainsItsExplanation() {
        showDetail()
        compose.onNodeWithText("AI 재구성 사진").assertDoesNotExist()
        compose.onNodeWithContentDescription("AI 이미지 안내").performClick()
        compose.onNodeWithText("가려진 부분은 실제와 다를 수", substring = true).assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithText("43–45 cm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("약 48cm · 추정").assertDoesNotExist()
        compose.onAllNodesWithText("가슴 단면").assertCountEquals(1)
        capture("measurements")
    }

    @Test fun longNamesAndLargeTextKeepManagementAndMeasurementsReachable() {
        showDetail(fontScale = 2f)
        compose.onNodeWithContentDescription("옷 관리 메뉴").assertIsDisplayed().performClick()
        compose.onNodeWithText("정보 수정").assertIsDisplayed()
        compose.onNodeWithText("삭제").assertIsDisplayed()
        compose.onNodeWithText("정보 수정").performClick()
        compose.onNodeWithText("43–45 cm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("메모").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("저장하기").assertIsDisplayed()
        capture("editor-large-text")
    }

    @Test fun ownedMatchesAndShoppingRecommendationsHaveSeparateGroupsAndActions() {
        val source = record()
        val owned = record().put("id", "pants").put("attributes", fixture().getJSONObject("attributes")
            .put("name", "내 검정 팬츠").put("category", "bottom"))
        val result = ExternalRecommendation("shirt", "bottom", "블랙 스트레이트 팬츠", "", "",
            ExternalAttributes("slacks", "블랙", "보통"), "https://www.musinsa.com/search/goods?keyword=slacks",
            listOf(WardrobeMatch("pants", "내 검정 팬츠")))
        compose.setContent { StyleMateTheme { Box(Modifier.width(320.dp).height(620.dp)) {
            GarmentMatchScreen(source, ExternalMatchState("shirt", result = result), listOf(source, owned),
                fixture().getJSONObject("catalog"), null, onBack = {}, onRetry = {},
                onItem = { events += "item:$it" }, onOpenLink = { events += "link:$it" },
                photo = { _, modifier -> SamplePhoto(modifier) })
        } } }
        compose.onNodeWithText("내 옷장에서 함께 입기").assertIsDisplayed()
        compose.onNodeWithText("내 검정 팬츠").assertIsDisplayed().performClick()
        compose.onNodeWithTag("garment-match-list").performScrollToNode(hasText("무신사에서 더 알아보기 ↗"))
        compose.onNode(hasText("이런 옷과 잘 어울려요") and hasAnyAncestor(hasTestTag("garment-shopping"))).assertExists()
        compose.onNode(hasText("무신사에서 더 알아보기 ↗") and hasAnyAncestor(hasTestTag("garment-shopping"))).performClick()
        capture("recommendations")
        compose.runOnIdle { assertEquals(listOf("item:pants", "link:${result.searchUrl}"), events) }
    }

    @Test fun emptyShoeFiltersStayHiddenAndExistingShoesRemainAccessible() {
        val top = WardrobeItem("top", "내 셔츠", "top")
        val shoe = WardrobeItem("shoe", "스니커즈", "shoes")
        val items = mutableStateOf(listOf(top))
        compose.setContent { StyleMateTheme { WardrobeScreen(items.value, null, false, {}) } }
        compose.onNodeWithText("옷장").assertIsDisplayed()
        compose.onNodeWithText("신발").assertDoesNotExist()
        capture("wardrobe")
        compose.runOnIdle { items.value = listOf(top, shoe) }
        compose.onNode(hasText("신발") and isSelectable()).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("스니커즈").assertIsDisplayed()
        compose.runOnIdle { items.value = listOf(top) }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithText("전체").assertIsSelected()
        compose.onNodeWithText("내 셔츠").assertIsDisplayed()
        compose.runOnIdle { items.value = listOf(top, shoe) }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithText("전체").assertIsSelected()
    }
}
