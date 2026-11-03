package com.swpp.stylemate.wardrobe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test

class WornPreviewTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(): WornUiFixture {
        val items = listOf("coat" to "코트", "pants" to "바지").map { (id, name) ->
            val attrs = JSONObject().put("name", name).put("category", if (id == "coat") "outerwear" else "bottom")
            val labels = JSONObject().put("fit", JSONObject.NULL).put("length", JSONObject.NULL).put("materials", JSONArray(listOf("데님")))
            val record = JSONObject().put("id", id).put("attributes", attrs).put("labels", labels)
                .put("details", JSONObject().put("materials", JSONArray(listOf("중간분석값"))))
            WornBatchItem(record, JSONObject().put("status", "ready").put("generated_url", "/api/wardrobe/items/$id/worn-image/candidate/"),
                JSONObject().put("attributes", attrs).put("labels", labels).put("notes", ""), imageChoice = "generated")
        }
        return WornUiFixture(items, JSONObject().put("dimensions", JSONObject()), emptyMap())
    }

    private fun show() {
        val sample = fixture()
        compose.setContent { StyleMateTheme { WornUiPreview(sample) } }
    }

    @Test fun localSelectionAndSaveSimulationWorkWithoutAnAccountOrProvider() {
        show()
        compose.onNodeWithText("오프라인 미리보기 · 예시 정보 · 실제 저장 없음").assertIsDisplayed()
        compose.onNodeWithText("전체 선택 (2/2)").assertIsDisplayed()
        compose.onNodeWithText("선택한 2벌 내 옷장에 등록").assertIsEnabled()
        compose.onNodeWithContentDescription("코트 등록 선택").performScrollTo().performClick()
        compose.onNodeWithText("선택한 1벌 내 옷장에 등록").performClick()
        compose.onNodeWithText("1벌 등록 동작을 확인했어요. 실제 저장은 하지 않았어요.").assertIsDisplayed()
    }

    @Test fun failedItemExplainsBlockedSaveAndCanBeExcludedLocally() {
        show()
        compose.onNodeWithContentDescription("미리보기 설정").performClick()
        compose.onNodeWithText("일부 실패").performClick()
        compose.onNodeWithText("선택한 2벌 내 옷장에 등록").assertIsNotEnabled()
        compose.onNodeWithText("사진을 만들지 못한 옷이 있어요. 다시 만들거나, 착용 사진을 사용하거나, 선택에서 빼 주세요.").assertIsDisplayed()
        compose.onNodeWithContentDescription("바지 등록 선택").performScrollTo().performClick()
        compose.onNodeWithText("선택한 1벌 내 옷장에 등록").assertIsEnabled()
    }

    @Test fun unknownFitDoesNotBlockAndFullScreenComparisonCanSwitchOriginal() {
        show()
        compose.onNodeWithContentDescription("미리보기 설정").performClick()
        compose.onNodeWithText("정보 미정").performClick()
        compose.onNodeWithText("선택한 2벌 내 옷장에 등록").assertIsEnabled()
        compose.onAllNodesWithText("원본 비교").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("원본").performClick()
        compose.onNodeWithText("원본 옷 영역").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("선택한 2벌 내 옷장에 등록").assertIsEnabled()
    }

    @Test fun selectAllCanToggleWithoutEditingAnyGarment() {
        show()
        compose.onNodeWithContentDescription("전체 선택").performClick()
        compose.onNodeWithText("전체 선택 (0/2)").assertIsDisplayed()
        compose.onNodeWithText("선택한 0벌 내 옷장에 등록").assertIsNotEnabled()
        compose.onNodeWithContentDescription("전체 선택").performClick()
        compose.onNodeWithText("선택한 2벌 내 옷장에 등록").assertIsEnabled()
    }

    @Test fun estimatesStayCompactUntilExpanded() {
        show()
        compose.onAllNodesWithContentDescription("추정 치수 펼치기").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("사진·체형을 바탕으로 추정한 참고값이에요.").assertIsDisplayed()
        compose.onNodeWithContentDescription("추정 치수 접기").performClick()
        compose.onNodeWithText("사진·체형을 바탕으로 추정한 참고값이에요.").assertDoesNotExist()
    }

    @Test fun materialComesFromExistingSavedLabelsInsteadOfIntermediateAnalysis() {
        show()
        compose.onAllNodesWithText("데님").onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("중간분석값").assertDoesNotExist()
    }
}
