package com.swpp.stylemate

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.*
import com.swpp.stylemate.ui.LocalAccountAccess
import com.swpp.stylemate.ui.profile.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class OnboardingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var store: LocalUserStore
    private lateinit var repository: AuthRepository
    private var rejectPatch = false
    private var patches = 0

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "onboarding-test-${UUID.randomUUID()}").apply { mkdirs() }
        store = LocalUserStore.inDirectory(root, ACCOUNT_ID)
        var user = AccountUser(ACCOUNT_ID, "member_1", null, StyleProfile()).json()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.header("Authorization") == "Bearer ${"a".repeat(43)}")
            var code = 200
            val body = when (request.url.encodedPath) {
                "/api/users/me/" -> {
                    if (request.method == "PATCH") {
                        patches++
                        if (rejectPatch) code = 503
                        else {
                            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                            val patch = JSONObject(buffer.readUtf8())
                            patch.keys().forEach { user.put(it, patch.get(it)) }
                        }
                    }
                    if (code == 200) JSONObject().put("user", user).toString() else "{}"
                }
                "/api/wardrobe/items/" -> JSONObject().put("items", JSONArray()).put("next", JSONObject.NULL).toString()
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = authenticatedRepository(client) { store.cacheAccountProfile(it) }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun model() = BodyProfileViewModel(FakeBodyAnalyzer(0), LocalBodyProfileStore(store), repository.account())

    @Test fun accountOnboardingSavesPreferencesResumesDraftAndCanSkipOptionalBodyAndClothes() {
        var current by mutableStateOf(model())
        var generation by mutableIntStateOf(0)
        val account = checkNotNull(repository.account())
        compose.setContent { StyleMateTheme { CompositionLocalProvider(LocalAccountAccess provides account) {
            key(generation) { OnboardingRoute(current, store) }
        } } }
        compose.onNodeWithText("이미 계정이 있나요? 로그인").assertDoesNotExist()
        compose.onNodeWithText("시작하기").performClick()
        compose.onNodeWithText("와이드").performClick()
        compose.onNodeWithText("다음").performClick()
        compose.waitUntil(5_000) { current.onboarding.value.step == 3 }
        compose.onNodeWithText("캐주얼").performClick()
        compose.runOnIdle {
            store.cacheAccountProfile(AccountUser(ACCOUNT_ID, "member_1", null, StyleProfile(preferredFits = listOf("wide"))))
            current = model(); generation++
        }
        compose.onNodeWithText("캐주얼").assertIsSelected()
        compose.onNodeWithText("다음").performClick()
        compose.waitUntil(5_000) { current.onboarding.value.step == 4 }
        compose.runOnIdle {
            assertEquals(2, patches)
            assertEquals(listOf("wide"), store.style().preferredFits)
            assertEquals(listOf("casual"), store.style().preferredStyles)
        }
        compose.onNodeWithText("키 (cm)").performTextInput("170")
        compose.runOnIdle { current = model(); generation++ }
        compose.onNodeWithText("키 (cm)").assertTextContains("170")
        compose.onNodeWithText("건너뛰기").performClick()
        compose.waitUntil(5_000) { current.onboarding.value.step == 6 }
        compose.onNodeWithText("건너뛰기").performClick()
        compose.waitUntil(5_000) { current.onboarding.value.step == 8 }
        compose.onNodeWithText("첫 코디 보러 가기").performClick()
        compose.runOnIdle { assertTrue(store.onboarding().completed); assertNull(current.app.value.profile) }
    }

    @Test fun failedAccountPreferenceSaveKeepsOnboardingStepAndAllowsRetry() {
        val current = model()
        current.moveOnboarding(2)
        rejectPatch = true
        val account = checkNotNull(repository.account())
        compose.setContent { StyleMateTheme { CompositionLocalProvider(LocalAccountAccess provides account) {
            OnboardingRoute(current, store)
        } } }
        compose.onNodeWithText("다음").performClick()
        compose.waitUntil(5_000) { current.setup.value.errorMessage != null && !current.setup.value.saving }
        compose.runOnIdle { assertEquals(2, current.onboarding.value.step); rejectPatch = false }
        compose.onNodeWithText("다음").performClick()
        compose.waitUntil(5_000) { current.onboarding.value.step == 3 }
        compose.runOnIdle { assertEquals(listOf("regular"), store.style().preferredFits) }
    }
}
