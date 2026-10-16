// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.*
import com.swpp.stylemate.ui.profile.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class OnboardingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var store: LocalUserStore

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "onboarding-test-${UUID.randomUUID()}").apply { mkdirs() }
        store = LocalUserStore.inDirectory(root)
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun model(analyzer: BodyAnalyzer = FakeBodyAnalyzer(0)) = BodyProfileViewModel(analyzer, LocalBodyProfileStore(store))
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "onboarding-preview-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun selectionsAndBodyInputResumeThenSkippingBodyAndClothesCompletes() {
        var current by mutableStateOf(model())
        var generation by mutableIntStateOf(0)
        compose.setContent { StyleMateTheme { key(generation) { OnboardingRoute(current, {}, store) } } }
        compose.onAllNodesWithText("건너뛰기").assertCountEquals(1)
        val startBounds = compose.onNodeWithText("시작하기").fetchSemanticsNode().boundsInRoot
        val loginBounds = compose.onNodeWithText("이미 계정이 있나요? 로그인").fetchSemanticsNode().boundsInRoot
        assertTrue(startBounds.bottom <= loginBounds.top)
        capture("01")
        compose.onNodeWithText("시작하기").performClick()
        compose.onNodeWithText("레귤러").assertIsNotSelected()
        compose.onNodeWithText("와이드").performScrollTo().performClick()
        capture("02")
        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("미니멀").performScrollTo().performClick()
        capture("03")
        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("키 (cm) *").performScrollTo().performTextInput("175")
        compose.runOnIdle { current = model(); generation++ }
        compose.onNodeWithText("키 (cm) *").performScrollTo().assertTextContains("175")
        compose.runOnIdle {
            assertEquals(4, current.onboarding.value.step)
            assertEquals(listOf("wide"), current.app.value.style.preferredFits)
            assertEquals(listOf("minimal"), current.app.value.style.preferredStyles)
            assertNull(current.setup.value.frontPhoto)
        }
        capture("04")
        compose.onAllNodesWithText("건너뛰기").assertCountEquals(1)
        compose.onNodeWithText("건너뛰기").performClick()
        compose.onNodeWithText("상의 0/2").assertIsDisplayed()
        compose.onNodeWithText("하의 0/2").assertIsDisplayed()
        compose.onNodeWithText("신발 0/1").assertIsDisplayed()
        capture("06")
        compose.onNodeWithText("다음").performClick()
        compose.onNodeWithText("시작할 준비가 됐어요!").assertIsDisplayed()
        capture("08")
        compose.onNodeWithText("첫 코디 보러 가기").performClick()
        compose.runOnIdle {
            assertTrue(current.onboarding.value.completed)
            assertFalse(current.app.value.setupVisible)
            assertNull(current.app.value.profile)
            assertTrue(model().onboarding.value.completed)
        }
    }

    @Test fun allFitsCanBeDeselectedAndOnlyNextAppliesTheRegularDefault() {
        var current by mutableStateOf(model())
        var generation by mutableIntStateOf(0)
        current.moveOnboarding(2)
        compose.setContent { StyleMateTheme { key(generation) { OnboardingRoute(current, {}, store) } } }
        compose.onNodeWithText("레귤러").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("레귤러").performClick().assertIsNotSelected()
        compose.onNodeWithText("와이드").performClick().assertIsSelected()
        compose.onNodeWithText("와이드").performClick().assertIsNotSelected()
        compose.runOnIdle { current = model(); generation++ }
        compose.onNodeWithText("레귤러").assertIsNotSelected()
        compose.onNodeWithText("와이드").assertIsNotSelected()
        compose.onNodeWithText("다음").performClick()
        compose.runOnIdle { assertEquals(listOf("regular"), store.style().preferredFits) }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("레귤러").assertIsNotSelected()
        compose.onNodeWithText("슬림").performScrollTo().performClick()
        compose.onNodeWithText("다음").performClick()
        compose.runOnIdle { assertEquals(listOf("slim"), store.style().preferredFits) }
    }

    @Test fun analysisReviewPersistsAndContinuesToWardrobeWithoutEndingOnboarding() {
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val model = model(object : BodyAnalyzer {
            override suspend fun analyze(input: AnalysisInput, photos: BodyPhotos): AnalysisResult {
                requests++
                release.await()
                return FakeBodyAnalyzer(0).analyze(input, photos)
            }
        })
        var backDispatcher: OnBackPressedDispatcher? = null
        model.moveOnboarding(4)
        compose.setContent {
            val owner = LocalOnBackPressedDispatcherOwner.current
            SideEffect { backDispatcher = owner?.onBackPressedDispatcher }
            StyleMateTheme { OnboardingRoute(model, {}, store) }
        }
        val photo = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            model.setHeight("175")
            model.setPhoto(PhotoSlot.FRONT, photo)
            model.setPhoto(PhotoSlot.SIDE, photo)
        }
        compose.onNodeWithText("분석하기").performClick()
        compose.waitUntil(5_000) { requests == 1 }
        compose.onNodeWithText("건너뛰기").assertIsNotEnabled()
        compose.runOnIdle {
            model.analyze()
            backDispatcher!!.onBackPressed()
            assertEquals(4, model.onboarding.value.step)
            assertEquals(SetupStep.ANALYZING, model.setup.value.step)
            assertEquals(1, requests)
            release.complete(Unit)
        }
        compose.waitUntil(5_000) { model.onboarding.value.step == 5 }
        compose.onNodeWithText("STEP 05 / 08").assertIsDisplayed()
        compose.onAllNodesWithText("건너뛰기").assertCountEquals(1)
        capture("05")
        compose.onNodeWithText("확인하고 계속하기").performClick()
        compose.runOnIdle {
            assertEquals(6, model.onboarding.value.step)
            assertFalse(model.onboarding.value.completed)
            assertNotNull(LocalBodyProfileStore(store).load())
        }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("STEP 05 / 08").assertIsDisplayed()
    }

    @Test fun loginIsOptionalAndCompletingBeforeLoginKeepsLocalPreferences() {
        val model = model()
        var loginRequests = 0
        compose.setContent { StyleMateTheme { OnboardingRoute(model, { loginRequests++ }, store) } }
        compose.onNodeWithText("이미 계정이 있나요? 로그인").performClick()
        compose.runOnIdle {
            assertEquals(1, loginRequests)
            assertFalse(model.onboarding.value.completed)
            model.updateOnboardingStyle(StyleProfile(listOf("wide"), listOf("minimal")))
            model.moveOnboarding(8)
        }
        compose.onNodeWithText("로그인 / 회원가입").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, loginRequests)
            assertTrue(model.onboarding.value.completed)
            assertEquals(listOf("wide"), store.style().preferredFits)
            assertEquals(listOf("minimal"), store.style().preferredStyles)
            assertNull(model.app.value.profile)
        }
    }

    @Test fun savedShoeDraftResumesEditingAndRegistersInTheSameLocalWardrobe() {
        val context = InstrumentationRegistry.getInstrumentation().context
        store.put("catalog", JSONObject(context.assets.open("wardrobe-editor.json").bufferedReader().use { it.readText() }).getJSONObject("catalog"))
        val attributes = JSONObject().put("name", "테스트 신발").put("category", "shoes").put("subcategory", "sneakers")
            .put("colors", JSONArray(listOf("#FFFFFF"))).put("styles", JSONArray())
        for (field in listOf("fit_type", "leg_shape", "rise_type", "skirt_shape")) attributes.put(field, JSONObject.NULL)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val image = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
        val garment = store.createGarment(JSONObject().put("attributes", attributes), image)
        var current by mutableStateOf(model())
        var generation by mutableIntStateOf(0)
        current.startOnboardingGarment("shoes")
        current.saveOnboardingGarment(JSONObject().put("category", "shoes").put("capture", "capture-123.jpg")
            .put("analysis_photo", "capture-123.jpg").put("result", garment))
        compose.setContent { StyleMateTheme { key(generation) { OnboardingRoute(current, {}, store) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("이름").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("이름").performScrollTo().performTextClearance()
        compose.onNodeWithText("이름").performTextInput("매일 신는 스니커즈")
        compose.waitForIdle()
        compose.runOnIdle { current = model(); generation++ }
        compose.onNodeWithText("이름").performScrollTo().assertTextContains("매일 신는 스니커즈")
        capture("07")
        compose.onNodeWithText("옷장에 추가").performClick()
        compose.waitUntil(10_000) { current.onboarding.value.step == 6 }
        compose.runOnIdle {
            assertEquals(1, store.garments().size)
            assertEquals("매일 신는 스니커즈", store.garments().single().getJSONObject("attributes").getString("name"))
            assertFalse(current.onboarding.value.completed)
        }
        compose.onNodeWithText("+ 신발 추가").performScrollTo().performClick()
        compose.onNodeWithText("자주 신는 신발을 담아볼까요?").assertIsDisplayed()
        compose.onNodeWithText("앨범에서 선택").assertIsDisplayed()
        compose.onNodeWithText("건너뛰기").performClick()
        compose.runOnIdle { assertEquals(6, current.onboarding.value.step); assertEquals(1, store.garments().size) }
    }
}
