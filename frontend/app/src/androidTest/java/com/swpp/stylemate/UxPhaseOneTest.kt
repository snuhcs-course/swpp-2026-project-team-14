package com.swpp.stylemate

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.*
import com.swpp.stylemate.data.chat.ChatRepository
import com.swpp.stylemate.data.recommendation.*
import com.swpp.stylemate.ui.AccountTabContent
import com.swpp.stylemate.ui.chat.ChatRoute
import com.swpp.stylemate.ui.chat.ChatViewModel
import com.swpp.stylemate.ui.profile.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.GarmentEditor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic photos and local UI state only: never submits a paid analysis or generation. */
class UxPhaseOneTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("wardrobe-editor.json").bufferedReader().use { it.readText() })

    @Test fun editorKeepsNameMeasurementsAndMemoAcrossTabsAndSeparatesSaveFromDiscard() {
        val data = fixture()
        var tab by mutableStateOf("wardrobe")
        var saved by mutableStateOf<JSONObject?>(null)
        var failure by mutableStateOf<String?>(null)
        compose.setContent { StyleMateTheme { AccountTabContent("account-a", tab) {
            if (tab == "home") Text("다른 탭") else {
                var editing by rememberSaveable { mutableStateOf(true) }
                if (!editing) TextButton(onClick = { editing = true }) { Text("다시 편집") }
                else GarmentEditor("garment-a", saved?.getJSONObject("attributes") ?: data.getJSONObject("attributes"),
                    saved?.optJSONObject("dimensions"), saved?.optString("notes") ?: "", data.getJSONObject("catalog"),
                    false, true, failure, onSave = { payload ->
                        if (failure != null) failure = null else { saved = payload; editing = false }
                    }, onBack = { editing = false })
            }
        } } }
        compose.onNodeWithText("이름").performScrollTo().performTextReplacement("수정한 이름")
        compose.onNodeWithText("가슴 단면").performScrollTo().performTextInput("53.25")
        compose.onNodeWithText("메모").performScrollTo().performTextInput("찬물 세탁")
        compose.runOnIdle { tab = "home" }
        compose.onNodeWithText("다른 탭").assertExists()
        compose.runOnIdle { tab = "wardrobe" }
        compose.onNodeWithText("이름").performScrollTo().assertTextContains("수정한 이름")
        compose.onNodeWithText("가슴 단면").performScrollTo().assertTextContains("53.25")
        compose.onNodeWithText("메모").performScrollTo().assertTextContains("찬물 세탁")
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("변경 내용을 버릴까요?").assertIsDisplayed()
        compose.onNodeWithText("계속 편집").performClick()
        compose.onNodeWithText("메모").assertTextContains("찬물 세탁")
        compose.runOnIdle { failure = "저장 연결 오류" }
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("메모").assertTextContains("찬물 세탁")
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals("수정한 이름", saved!!.getJSONObject("attributes").getString("name")) }
        compose.onNodeWithText("다시 편집").performClick()
        compose.onNodeWithText("이름").performScrollTo().performTextReplacement("버릴 이름")
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("버리기").performClick()
        compose.onNodeWithText("다시 편집").performClick()
        compose.onNodeWithText("이름").performScrollTo().assertTextContains("수정한 이름")
        compose.onNodeWithText("버릴 이름").assertDoesNotExist()
    }

    @Test fun draftsDoNotCrossGarmentAccountOrLogoutBoundaries() {
        val data = fixture()
        var account by mutableStateOf("account-a")
        var item by mutableStateOf("garment-a")
        var signedIn by mutableStateOf(true)
        compose.setContent { StyleMateTheme {
            if (signedIn) AccountTabContent(account, "wardrobe") {
                key(item) { GarmentEditor(item, data.getJSONObject("attributes"), null, "", data.getJSONObject("catalog"),
                    false, true, null, {}, onBack = {}) }
            } else Text("로그인 화면")
        } }
        compose.onNodeWithText("이름").performScrollTo().performTextReplacement("계정 A 초안")
        compose.runOnIdle { item = "garment-b" }
        compose.onNodeWithText("이름").performScrollTo().assertTextContains("흰색 티셔츠")
        compose.onNodeWithText("이름").performTextReplacement("다른 옷 초안")
        compose.runOnIdle { account = "account-b" }
        compose.onNodeWithText("이름").performScrollTo().assertTextContains("흰색 티셔츠")
        compose.runOnIdle { account = "account-a" }
        compose.onNodeWithText("이름").performScrollTo().assertTextContains("흰색 티셔츠")
        compose.onNodeWithText("이름").performTextReplacement("로그아웃 전 초안")
        compose.runOnIdle { signedIn = false }
        compose.onNodeWithText("로그인 화면").assertExists()
        compose.runOnIdle { signedIn = true }
        compose.onNodeWithText("이름").performScrollTo().assertTextContains("흰색 티셔츠")
    }

    @Test fun photoBoxSupportsCancelledGalleryCameraGuideAndIndependentDelete() {
        val image = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        var state by mutableStateOf(SetupState(frontPhoto = image, sidePhoto = image, heightText = "172", weightText = "65"))
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                launches++
                when (contract) {
                    is ActivityResultContracts.PickVisualMedia -> dispatchResult(requestCode, null as Uri?)
                    is ActivityResultContracts.TakePicture -> dispatchResult(requestCode, false)
                    else -> error("Unexpected activity: $contract")
                }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { StyleMateTheme {
            val base = LocalContext.current
            val cameraAllowed = remember { object : ContextWrapper(base) {
                override fun checkPermission(permission: String, pid: Int, uid: Int) =
                    if (permission == android.Manifest.permission.CAMERA) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)
                override fun checkSelfPermission(permission: String) =
                    if (permission == android.Manifest.permission.CAMERA) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
            } }
            CompositionLocalProvider(LocalContext provides cameraAllowed, LocalActivityResultRegistryOwner provides owner) {
                PhotoInputScreen(state, {}, { slot, value -> state = if (slot == PhotoSlot.FRONT) state.copy(frontPhoto = value) else state.copy(sidePhoto = value) },
                    { state = state.copy(errorMessage = it) }, { state = state.copy(heightText = it) },
                    { state = state.copy(weightText = it) }, { state = state.copy(gender = it) }, {})
            }
        } }
        compose.onNodeWithText("카메라").assertDoesNotExist()
        compose.onNodeWithContentDescription("정면 사진 선택").performScrollTo().performClick()
        compose.onNodeWithText("앨범").performClick()
        compose.runOnIdle { assertSame(image, state.frontPhoto); assertNull(state.errorMessage) }
        compose.onNodeWithContentDescription("정면 사진 선택").performClick()
        compose.onNodeWithText("카메라").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertSame(image, state.frontPhoto); assertNull(state.errorMessage); assertEquals(2, launches) }
        compose.onNodeWithText("촬영 예시").performScrollTo().performClick()
        compose.onNodeWithText("체형 촬영 가이드").assertIsDisplayed()
        compose.onNodeWithText("사진 입력으로 돌아가기").performClick()
        compose.onNodeWithText("키 (cm) *").performScrollTo().assertTextContains("172")
        compose.onNodeWithText("몸무게 (kg)").assertTextContains("65")
        compose.onNodeWithContentDescription("정면 사진 지우기").performScrollTo().performClick()
        compose.onNodeWithText("정면 사진 선택").assertDoesNotExist()
        compose.runOnIdle { assertNull(state.frontPhoto); assertSame(image, state.sidePhoto); assertEquals(2, launches) }
    }

    @Test fun groupedChatChoicesReplaceToggleAndCombineWithoutSending() {
        val account = checkNotNull(authenticatedRepository().account())
        val model = ChatViewModel(ChatRepository("http://localhost", account), InMemoryTasteStore())
        // A current garment keeps canSend meaningful; no service request is needed.
        val outfit = Outfit("source-2", "key-2", null, listOf(OutfitGarment("top", "g1", "상의", "", "")), emptyList(), false, "", null, "")
        model.open(outfit, BodyProfile(AnalysisInput(170, 65, Gender.MALE), emptyList(), PreferredFit.REGULAR, emptyList()), null)
        compose.setContent { StyleMateTheme { ChatRoute(model) } }
        compose.onNodeWithContentDescription("메시지 보내기").assertIsNotEnabled()
        compose.onNodeWithContentDescription("수정 조건 선택").performClick()
        compose.onNodeWithText("밝기").assertIsDisplayed()
        compose.onNodeWithTag("chat-direction-brighter").performClick()
        compose.onNodeWithTag("chat-direction-darker").performClick()
        compose.runOnIdle { assertEquals(setOf("darker"), model.state.value.directions) }
        compose.onNodeWithTag("chat-direction-brighter").assertIsNotSelected()
        compose.onNodeWithTag("chat-direction-darker").assertIsSelected()
        compose.onNodeWithText("단정하게").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("darker", "formal"), model.state.value.directions) }
        compose.onNodeWithText("선택한 조건으로 수정").assertDoesNotExist()
        compose.onNodeWithTag("chat-direction-darker").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("formal"), model.state.value.directions); assertTrue(model.state.value.turns.isEmpty()) }
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithContentDescription("메시지 보내기").assertIsEnabled()
    }

    @Test fun profileOnlyOffersImportForAnEligibleSignedInUser() {
        var eligible by mutableStateOf(false)
        var requested = 0
        compose.setContent { StyleMateTheme { MyProfileScreen(null, StyleProfile(), { null }, {}, {},
            username = "member_1", onImport = if (eligible) ({ requested++ }) else null) } }
        compose.onNodeWithText("이 기기의 기존 데이터 가져오기").assertDoesNotExist()
        compose.runOnIdle { eligible = true }
        compose.onNodeWithContentDescription("이 기기의 데이터 가져오기").assertDoesNotExist()
        compose.onNodeWithText("이 기기의 기존 데이터 가져오기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requested) }
    }

    @Test fun bodyInputKeepsFocusedErrorAndAnalysisActionAboveTheKeyboard() {
        var keyboardHeight by mutableIntStateOf(0)
        compose.setContent { StyleMateTheme {
            val height = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { keyboardHeight = height }
            PhotoInputScreen(SetupState(heightText = "90"), {}, { _, _ -> }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("키 (cm) *").performScrollTo().performClick()
        compose.waitUntil(5_000) { keyboardHeight > 0 }
        compose.onNodeWithText("키 (cm) *").assertIsDisplayed()
        compose.onNodeWithText("100~220 사이로 입력").assertIsDisplayed()
        compose.onNodeWithText("분석하기").assertIsDisplayed()
        val error = compose.onNodeWithText("100~220 사이로 입력").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithText("분석하기").fetchSemanticsNode().boundsInRoot
        assertTrue("Focused error must stay above the analysis action", error.bottom <= action.top)
    }
}
