package com.swpp.stylemate

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.*
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.StyleMateApp
import com.swpp.stylemate.ui.chat.ChatScreen
import com.swpp.stylemate.ui.chat.ChatState
import com.swpp.stylemate.ui.profile.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.tryon.PhotoFittingContent
import com.swpp.stylemate.ui.wardrobe.GarmentEditor
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real app navigation and service composables, isolated API responses and no paid model calls. */
class GlobalThemeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var ownDirectory: File? = null
    @After fun cleanup() { ownDirectory?.deleteRecursively() }

    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("wardrobe-editor.json").bufferedReader().use { it.readText() })

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val legacy = setOf(0xFFC4552B.toInt(), 0xFFF8F6F2.toInt(), 0xFFF6DDD2.toInt(), 0xFF8A3312.toInt())
        assertEquals("Legacy brand colours remain on $name", 0, pixels.count { it in legacy })
        val directory = File(context.getExternalFilesDir(null), "global-theme").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun realAppTabsUseTheSameThemeIncludingSelectedNavigation() {
        val id = UUID.randomUUID().toString()
        ownDirectory = File(context.filesDir, "local-users/$id")
        val user = AccountUser(id, "theme_preview", null, StyleProfile())
        val session = AuthSession("z".repeat(43), Instant.parse("2099-01-01T00:00:00Z"), user)
        val catalog = fixture().getJSONObject("catalog")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = when (request.url.encodedPath) {
                "/api/users/me/" -> JSONObject().put("user", user.json())
                "/api/wardrobe/items/" -> JSONObject().put("items", JSONArray()).put("next", JSONObject.NULL)
                "/api/wardrobe/options/" -> catalog
                "/api/outfits/" -> JSONObject().put("outfits", JSONArray()).put("next", JSONObject.NULL)
                "/api/weather/" -> JSONObject().put("temp", 18).put("apparent", 18).put("label", "맑음")
                "/api/outfits/recommend/" -> JSONObject().put("outfits", JSONArray()).put("notes", JSONArray())
                else -> JSONObject()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = AuthRepository("http://localhost", object : AuthSessionStore {
            override fun read() = session.json().toString()
            override fun write(value: String) = Unit
            override fun clear() = Unit
        }, client)
        val auth = AuthViewModel(repository)
        val profile = BodyProfileViewModel(FakeBodyAnalyzer(0)).apply { skipSetup() }
        compose.setContent { StyleMateTheme { StyleMateApp(profile, auth) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("MY").fetchSemanticsNodes().isNotEmpty() }
        capture("01-home-with-navigation")
        compose.onNode(hasText("옷장") and isSelectable()).performClick().assertIsSelected()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("아직 등록된 옷이 없습니다").fetchSemanticsNodes().isNotEmpty() }
        capture("02-wardrobe-with-navigation")
        compose.onNodeWithText("등록하기").performClick()
        compose.onNodeWithText("사진으로 등록").assertIsDisplayed()
        compose.onNodeWithText("직접 촬영하기").assertIsDisplayed()
        capture("03-registration-sheet")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNode(hasText("코디북") and isSelectable()).performClick().assertIsSelected()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("저장한 코디 0개").fetchSemanticsNodes().isNotEmpty() }
        capture("04-codibook-with-navigation")
        compose.onNode(hasText("MY") and isSelectable()).performClick().assertIsSelected()
        compose.onNodeWithText("내 체형 정보").assertIsDisplayed()
        capture("05-profile-with-navigation")
    }

    @Test fun authEditorBodyChatAndFittingShareTheAppTheme() {
        var page by mutableStateOf("login")
        val data = fixture()
        val outfit = Outfit("theme-outfit", "theme-key", null,
            listOf(OutfitGarment("top", "g1", "상의", "", "")), emptyList(), false, "", null, "")
        compose.setContent { StyleMateTheme { Surface(Modifier.fillMaxSize()) {
            when (page) {
                "login" -> LoginScreen(AuthUiState(), { page = "signup" }, { _, _ -> })
                "signup" -> RegistrationScreen(AuthUiState(), { page = "login" }, { _, _, _ -> })
                "editor" -> GarmentEditor("theme-garment", data.getJSONObject("attributes"), null, "",
                    data.getJSONObject("catalog"), false, true, null, {}, onBack = {})
                "body" -> PhotoInputScreen(SetupState(), {}, { _, _ -> }, {}, {}, {}, {}, {})
                "chat" -> ChatScreen(ChatState(source = outfit, current = outfit), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                "fitting" -> PhotoFittingContent(false, null, null, {}, {}, {}, {})
            }
        } } }
        capture("06-login")
        compose.onNodeWithText("회원가입").performClick()
        compose.onNodeWithText("비밀번호 확인").assertExists()
        capture("07-signup")
        for ((screen, name) in listOf("editor" to "08-editor", "body" to "09-body-input",
            "chat" to "10-chat", "fitting" to "11-fitting")) {
            compose.runOnIdle { page = screen }
            capture(name)
        }
    }
}
