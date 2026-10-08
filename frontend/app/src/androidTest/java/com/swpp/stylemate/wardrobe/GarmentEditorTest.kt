// AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.wardrobe.*
import com.swpp.stylemate.ui.wardrobe.*

import com.swpp.stylemate.ui.theme.StyleMateTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GarmentEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("wardrobe-editor.json").bufferedReader().use { it.readText() })

    @Test fun compactAnalysisStillAllowsBottomMeasurementsWithoutSubtype() {
        val fixture = fixture()
        val attributes = fixture.getJSONObject("attributes").put("category", "bottom").put("subcategory", JSONObject.NULL)
        var submitted: JSONObject? = null
        compose.setContent { StyleMateTheme {
            GarmentEditor("minimal", attributes, null, "", fixture.getJSONObject("catalog"),
                false, false, null, { submitted = it })
        } }
        compose.onNodeWithText("종류: 미입력").assertDoesNotExist()
        compose.onNodeWithText("허리 단면").performScrollTo().performTextInput("37.25")
        compose.onNodeWithText("옷장에 추가").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(37.25, requireNotNull(submitted).getJSONObject("dimensions").getJSONObject("waist_width_half").getDouble("value"), .0001)
        }
    }

    @Test fun editedMeasurementsAndMemoAreSentWithoutOverwritingOtherEstimates() {
        val fixture = fixture()
        var submitted: JSONObject? = null
        val dimensions = JSONObject("""{"unit":"cm","chest_width_half":{"value":55.12,"source":"arcore_assisted","method":"flat_underarm_to_underarm","reference":null},"total_length":{"value":65.34,"source":"arcore_manual","method":"back_neck_to_hem","reference":null},"shoulder_width":{"value":45.12,"source":"arcore_assisted","method":"flat_shoulder_seam_to_seam","reference":null}}""")
        compose.setContent { StyleMateTheme {
            GarmentEditor("draft", fixture.getJSONObject("attributes"), dimensions, "",
                fixture.getJSONObject("catalog"), false, false, null, { submitted = it })
        } }
        compose.onNodeWithText("AR 추정").assertDoesNotExist()
        compose.onNodeWithText("착용 정보").assertDoesNotExist()
        compose.onNodeWithText("상세 정보").performScrollTo().performClick()
        listOf("격식:", "여밈:", "디테일:", "어깨:", "넥라인:", "소매:").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText("핏: 미입력").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("상세 정보").performScrollTo().performClick()
        compose.onNodeWithText("이름").performScrollTo().performTextReplacement("내 흰 티셔츠")
        compose.onNodeWithText("가슴 단면").performScrollTo().performTextReplacement("56.78")
        compose.onNodeWithText("메모").performScrollTo().performTextInput("찬물 세탁\n여행용")
        compose.onNodeWithText("옷장에 추가").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val payload = requireNotNull(submitted)
            assertEquals("내 흰 티셔츠", payload.getJSONObject("attributes").getString("name"))
            assertEquals("찬물 세탁\n여행용", payload.getString("notes"))
            assertFalse(payload.has("user_properties"))
            val chest = payload.getJSONObject("dimensions").getJSONObject("chest_width_half")
            assertEquals(56.78, chest.getDouble("value"), 0.00001)
            assertEquals("user_measured", chest.getString("source"))
            assertEquals("unspecified", chest.getString("method"))
            assertEquals("arcore_manual", payload.getJSONObject("dimensions").getJSONObject("total_length").getString("source"))
            assertEquals("arcore_assisted", payload.getJSONObject("dimensions").getJSONObject("shoulder_width").getString("source"))
        }
    }

    @Test fun invalidMeasurementBlocksSaveAndChangingCategoryClearsMeasurements() {
        val fixture = fixture()
        var submitted: JSONObject? = null
        compose.setContent { StyleMateTheme {
            GarmentEditor("draft", fixture.getJSONObject("attributes"), null, "기존 메모",
                fixture.getJSONObject("catalog"), false, true, null, { submitted = it })
        } }
        compose.onNodeWithText("가슴 단면").performScrollTo().performTextInput("12.345")
        compose.onNodeWithText("저장하기").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("분류: 상의").performScrollTo().performClick()
        compose.onNodeWithText("신발").performClick()
        compose.onNodeWithText("가슴 단면").assertDoesNotExist()
        compose.onNodeWithText("저장하기").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(requireNotNull(submitted).isNull("dimensions"))
            assertEquals("기존 메모", requireNotNull(submitted).getString("notes"))
            assertTrue(requireNotNull(submitted).getJSONObject("attributes").isNull("subcategory"))
        }
    }
}
