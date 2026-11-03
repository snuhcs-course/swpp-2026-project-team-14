package com.swpp.stylemate.wardrobe

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.*
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.wardrobe.AutoGarmentCandidates
import com.swpp.stylemate.ui.wardrobe.WornBatchItem
import com.swpp.stylemate.ui.wardrobe.canSaveWornBatch
import com.swpp.stylemate.ui.wardrobe.wornBatchSaveProblem
import com.swpp.stylemate.ui.wardrobe.GarmentEstimates
import androidx.compose.runtime.*
import org.json.JSONObject
import org.json.JSONArray
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.WardrobeScreen
import com.swpp.stylemate.ui.wardrobe.cropWornPhoto
import com.swpp.stylemate.ui.wardrobe.detectionErrorMessage
import com.swpp.stylemate.ui.wardrobe.WornBatchReviewScreen
import com.swpp.stylemate.ui.wardrobe.WornBatchState
import com.swpp.stylemate.ui.wardrobe.WornPhotoUpload
import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WornRegistrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerErrorsAreNotReportedAsUnidentifiableClothing() {
        assertTrue(detectionErrorMessage("AI_REQUEST_INVALID").contains("서버"))
        assertTrue(detectionErrorMessage("AI_PROVIDER_ERROR").contains("서비스"))
        assertTrue(detectionErrorMessage("AI_INVALID_RESPONSE").contains("결과"))
        assertTrue(detectionErrorMessage("IMAGE_TARGET_UNCLEAR").contains("옷을 구분하지 못했어요"))
        assertFalse(detectionErrorMessage("DETECTION_FAILED").contains("사진에서 옷을 찾지 못했어요"))
    }

    @Test fun selectedRegionKeepsOriginalPixelsAndRejectsTinySelection() {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        bitmap.setPixel(50, 60, Color.RED)
        val crop = cropWornPhoto(bitmap, 0.25f..0.75f, 0.2f..0.8f)
        assertEquals(100, crop.width)
        assertEquals(180, crop.height)
        assertEquals(Color.RED, crop.getPixel(0, 0))
        assertThrows(IllegalArgumentException::class.java) { cropWornPhoto(bitmap, 0.5f..0.51f, 0f..1f) }
    }

    @Test fun wardrobeOffersBothRegistrationPaths() {
        var ar = false
        var worn = false
        compose.setContent {
            StyleMateTheme {
                WardrobeScreen(emptyList(), null, false, onCapture = { ar = true }, onWornPhoto = { worn = true })
            }
        }
        compose.onNodeWithText("착용 사진으로 등록").assertDoesNotExist()
        compose.onNodeWithText("등록하기").performClick()
        compose.onNodeWithText("직접 촬영하기").assertIsDisplayed()
        compose.onNodeWithText("착용 사진으로 등록").performClick()
        compose.runOnIdle { assertTrue(worn); assertFalse(ar) }
        compose.onNodeWithText("등록하기").performClick()
        compose.onNodeWithText("직접 촬영하기").performClick()
        compose.runOnIdle { assertTrue(ar) }
    }

    @Test fun automaticCandidatesStartSelectedAndCanBeToggledWithoutSliders() {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        val candidates = listOf("outerwear" to "코트", "top" to "니트").map { (id, name) ->
            JSONObject().put("id", id).put("bounds", JSONArray(listOf(100, 100, 900, 900)))
                .put("attributes", JSONObject().put("name", name))
        }
        var selected by mutableStateOf(setOf("outerwear", "top"))
        compose.setContent {
            StyleMateTheme {
                Column(Modifier.fillMaxWidth()) {
                    AutoGarmentCandidates(bitmap, candidates, selected, true) {
                        selected = if (it in selected) selected - it else selected + it
                    }
                }
            }
        }
        compose.onNodeWithText("2. 니트").performClick()
        compose.runOnIdle { assertEquals(setOf("outerwear"), selected) }
        compose.onNodeWithText("2. 니트").performClick()
        compose.runOnIdle { assertEquals(setOf("outerwear", "top"), selected) }
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().size)
    }

    @Test fun multiSaveAllowsUnknownFitLengthAndExplainsUnreadyImages() {
        val item = WornBatchItem(JSONObject().put("id", "coat"), JSONObject().put("status", "ready"),
            JSONObject().put("attributes", JSONObject().put("name", "코트"))
                .put("labels", JSONObject().put("fit", "루즈").put("length", "롱")))
        assertFalse(canSaveWornBatch(listOf(item))) // no image approval
        val approved = item.copy(imageChoice = "generated")
        assertTrue(canSaveWornBatch(listOf(approved)))
        val pending = item.copy(generation = JSONObject().put("status", "pending"))
        assertFalse(canSaveWornBatch(listOf(approved, pending)))
        assertTrue(canSaveWornBatch(listOf(approved, pending.copy(included = false))))
        val missingFit = JSONObject(approved.payload.toString()).apply { getJSONObject("labels").put("fit", JSONObject.NULL) }
        assertTrue(canSaveWornBatch(listOf(approved.copy(payload = missingFit))))
        assertNull(wornBatchSaveProblem(listOf(approved.copy(payload = missingFit))))
        assertNotNull(wornBatchSaveProblem(listOf(pending)))
        assertFalse(canSaveWornBatch(emptyList()))
    }

    @Test fun estimatedDimensionsShowApproximateValueRangeAndAreReadOnly() {
        val attrs = JSONObject().put("category", "top").put("subcategory", "tshirt")
        val estimate = JSONObject().put("category", "top").put("subcategory", "tshirt")
            .put("source", "body_photo_estimate").put("measurements", JSONObject().put("chest_width_half",
                JSONObject().put("value", 53).put("min", 49).put("max", 57)))
        val catalog = JSONObject().put("dimensions", JSONObject().put("top", JSONObject().put("chest_width_half",
            JSONObject().put("label", "가슴 단면"))))
        compose.setContent { StyleMateTheme { Column { GarmentEstimates(estimate, attrs, catalog) } } }
        compose.onNodeWithText("추정 치수").assertIsDisplayed()
        compose.onNodeWithText("약 53cm · 추정").assertIsDisplayed()
        compose.onNodeWithText("예상 범위 49~57cm").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes().size)
    }

    @Test fun uploadAreaOpensGalleryAndDisablesWhileProcessing() {
        var picks = 0
        var enabled by mutableStateOf(true)
        compose.setContent { StyleMateTheme {
            WornPhotoUpload(false, enabled) { picks++ }
        } }
        compose.onNodeWithContentDescription("착용 사진 업로드").performClick()
        compose.runOnIdle { assertEquals(1, picks); enabled = false }
        compose.onNodeWithContentDescription("착용 사진 업로드").assertIsNotEnabled()
        compose.onNodeWithText("직접 한 벌 지정").assertDoesNotExist()
    }

    private fun reviewItem(status: String = "ready"): WornBatchItem {
        val attrs = JSONObject().put("name", "검증 코트").put("category", "outerwear")
        val labels = JSONObject().put("fit", JSONObject.NULL).put("length", JSONObject.NULL)
        return WornBatchItem(JSONObject().put("id", "coat"),
            JSONObject().put("status", status).put("generated_url", "/api/wardrobe/items/coat/worn-image/candidate/"),
            JSONObject().put("attributes", attrs).put("labels", labels),
            imageChoice = if (status == "ready") "generated" else null)
    }

    @Test fun serviceReviewUsesCompactCardsAndFullImageComparisonWithoutPhotoAddition() {
        var edits = 0
        var saves = 0
        var crops = 0
        var regenerations = 0
        val scales = mutableSetOf<ContentScale>()
        compose.setContent { StyleMateTheme {
            WornBatchReviewScreen(WornBatchState(items = listOf(reviewItem())), JSONObject(), {}, { saves++ },
                { _, _ -> }, { edits++ }, { regenerations++ }, { crops++ }, {},
                photo = { label, _, _, modifier, scale ->
                    SideEffect { scales.add(scale) }
                    androidx.compose.material3.Text(label, modifier)
                })
        } }
        compose.onNodeWithText("등록할 옷 확인").assertIsDisplayed()
        compose.onNodeWithText("전체 선택 (1/1)").assertIsDisplayed()
        compose.onNodeWithText("사진 추가").assertDoesNotExist()
        compose.onNodeWithText("다른 사진으로 다시 시작").assertDoesNotExist()
        compose.onNodeWithText("수정").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, edits); assertTrue(scales.contains(ContentScale.Crop)) }
        compose.onNodeWithText("원본 비교").performScrollTo().performClick()
        compose.onNodeWithText("원본").performClick()
        compose.onNodeWithText("원본 옷 영역").assertIsDisplayed()
        compose.runOnIdle { assertTrue(scales.contains(ContentScale.Fit)) }
        compose.onNodeWithText("원본 사진으로 사용").performClick()
        compose.runOnIdle { assertEquals(1, crops); assertEquals(0, regenerations) }
        compose.onNodeWithText("선택한 1벌 내 옷장에 등록").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun generatingServiceReviewShowsSpinnerAndPreventsSaveOrEdit() {
        compose.setContent { StyleMateTheme {
            WornBatchReviewScreen(WornBatchState(items = listOf(reviewItem("running")), busy = true,
                progress = "옷 분리·이미지 생성 중 · 1/1"), JSONObject(), {}, {}, { _, _ -> }, {}, {}, {}, {},
                photo = { _, _, _, _, _ -> })
        } }
        compose.onNodeWithContentDescription("처리 중").assertIsDisplayed()
        compose.onNodeWithText("옷 분리·이미지 생성 중 · 1/1").assertIsDisplayed()
        compose.onNodeWithText("처리 중").assertIsNotEnabled()
        compose.onNodeWithText("수정").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("다시 만들기").assertDoesNotExist()
    }

}
