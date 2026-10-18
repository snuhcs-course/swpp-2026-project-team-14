package com.swpp.stylemate.wardrobe

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GarmentLabelFlowTest {
    @get:Rule val compose = createComposeRule()
    private val options = JSONObject("""{"top":{"fit":["타이트","노멀","루즈","오버사이즈"],
        "length":["크롭","노멀","롱"],"sleeve":["반팔","긴팔"],"season":["여름","간절기","겨울"],
        "prints":["무지","체크"],"neckline":["라운드넥"],"collar":["셔츠칼라"],
        "design_details":["포켓"],"materials":["저지"]},"limits":{"prints":2,"materials":2,"design_details":3}}""")
    private var saved: JSONObject? = null

    private fun screen(response: () -> Pair<Int, String>): GarmentLabelsViewModel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = response()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(body.toResponseBody()).build()
        }.build()
        val model = GarmentLabelsViewModel(OutfitRepository(checkNotNull(com.swpp.stylemate.authenticatedRepository(client).account())))
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("wardrobe-editor.json").bufferedReader().use { it.readText() })
        val attrs = fixture.getJSONObject("attributes").put("category", "top")
        val catalog = fixture.getJSONObject("catalog").put("label_options", options)
        compose.setContent { StyleMateTheme {
            val state by model.state.collectAsStateWithLifecycle()
            GarmentEditor("test", attrs, null, "", catalog, false, false, null,
                onSave = { saved = model.merged("top", options) },
                canSaveLabels = { a, d -> GarmentLabels.canSave(state, "test", a, d, null) },
                labels = { a, d -> GarmentLabelSection(model, "test", a, d, null, null, null, options, true) })
        } }
        return model
    }

    @Test fun failedCalculationKeepsManualFieldsAndRequiresExplicitConfirmation() {
        val model = screen { 503 to """{"error":"UNAVAILABLE"}""" }
        compose.waitUntil(5000) { model.state.value.error != null }
        compose.onNodeWithText("옷장에 추가").assertIsNotEnabled()
        compose.onNodeWithText("루즈").performScrollTo().performClick()
        compose.onNodeWithText("옷장에 추가").assertIsNotEnabled()
        compose.onNodeWithText("수동 입력 완료").performScrollTo().performClick()
        compose.onNodeWithText("옷장에 추가").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("루즈", saved!!.getString("fit"))
            assertTrue(saved!!.getBoolean("confirmed"))
        }
    }

    @Test fun retryBlocksSaveUntilFreshLabelsReturnAndKeepsManualEdits() {
        val count = AtomicInteger()
        val gate = CountDownLatch(1)
        val model = screen {
            if (count.incrementAndGet() == 1) 503 to """{"error":"UNAVAILABLE"}"""
            else {
                check(gate.await(10, TimeUnit.SECONDS))
                200 to """{"labels":{"fit":"노멀","season":"여름"}}"""
            }
        }
        try {
            compose.waitUntil(5000) { model.state.value.error != null }
            compose.onNodeWithText("루즈").performScrollTo().performClick()
            compose.onNodeWithText("다시 계산").performScrollTo().performClick()
            compose.waitUntil(5000) { model.state.value.loading }
            compose.onNodeWithText("옷장에 추가").assertIsNotEnabled()
            gate.countDown()
            compose.waitUntil(5000) { !model.state.value.loading && model.state.value.error == null }
            compose.onNodeWithText("옷장에 추가").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals("루즈", saved!!.getString("fit"))
                assertEquals("여름", saved!!.getString("season"))
            }
        } finally { gate.countDown() }
    }
}
