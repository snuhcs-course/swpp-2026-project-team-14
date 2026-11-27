package com.swpp.stylemate.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.swpp.stylemate.data.*
import com.swpp.stylemate.data.recommendation.HomeRegion
import com.swpp.stylemate.data.recommendation.InMemoryTasteStore
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.Region
import com.swpp.stylemate.ui.LocalAccountAccess
import com.swpp.stylemate.ui.home.HomeRoute
import com.swpp.stylemate.ui.home.HomeViewModel
import com.swpp.stylemate.ui.theme.StyleMateTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Exercises HomeRoute's persisted selection, not a screen supplied with a replacement state holder. */
class HomeRegionSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val incheon = Region("송도", 37.3826, 126.6566, "인천광역시 연수구")
    private val pohang = Region("송도", 36.0184, 129.3727, "경상북도 포항시 남구")
    private lateinit var account: AccountAccess
    private lateinit var store: LocalUserStore
    private lateinit var model: HomeViewModel

    @Before fun setup() = runBlocking {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/api/weather/places/") -> MockResponse().setBody(
                    JSONObject().put("places", JSONArray(listOf(incheon, pohang).map {
                        JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("area", it.area)
                    })).toString())
                request.path.orEmpty().startsWith("/api/weather/") -> MockResponse().setBody(
                    """{"temp":18,"apparent":18,"label":"맑음","band":"간절기","rain":false,"snow":false}""")
                request.path == "/api/outfits/recommend/" -> MockResponse().setBody(
                    """{"outfits":[],"notes":[],"band":{"label":"간절기"}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val session = AuthSession("a".repeat(43), Instant.parse("2099-01-01T00:00:00Z"),
            AccountUser(UUID.randomUUID().toString(), "region_fixture", null, StyleProfile()))
        val auth = AuthRepository(server.url("/").toString(), object : AuthSessionStore {
            override fun read() = session.json().toString()
            override fun write(value: String) = Unit
            override fun clear() = Unit
        })
        auth.restore()
        account = checkNotNull(auth.account())
        store = LocalUserStore.forContext(context, account.userId)
        HomeRegion.save(store, incheon)
        model = HomeViewModel(OutfitRepository(account), InMemoryTasteStore())
        model.setRegion(incheon, reload = false)
    }

    @After fun cleanup() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        server.shutdown()
        if (::account.isInitialized) File(context.filesDir, "local-users/${account.userId}").deleteRecursively()
    }

    @Test fun sameNamePlacesKeepTheirCoordinatesWhenReopeningAndReturningHome() {
        val showHome = mutableStateOf(true)
        compose.setContent { StyleMateTheme {
            CompositionLocalProvider(LocalAccountAccess provides account) {
                if (showHome.value) HomeRoute(null, {}, {}, onChat = { _, _ -> }, model = model)
            }
        } }

        fun openPicker(expected: Region) {
            compose.onNodeWithText("송도").performClick()
            compose.onNodeWithText(expected.area).assertIsDisplayed()
            compose.onNodeWithContentDescription("지역 지도, 송도 선택됨").assertIsDisplayed()
        }
        fun pick(place: Region) {
            val searchField = compose.onNode(hasSetTextAction())
            searchField.performTextInput("송도")
            searchField.performImeAction()
            closeSoftKeyboard()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("검색 결과").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(place.area).onLast().performScrollTo().performClick()
            compose.onAllNodesWithText(place.area).assertCountEquals(2)
            // Exercise selection persistence independently of the sheet height on small CI devices.
            compose.onNodeWithText("송도 날씨로 추천받기").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            compose.waitUntil(5_000) { HomeRegion.load(store) == place }
            assertEquals(place, HomeRegion.load(store))
        }

        openPicker(incheon)
        pick(pohang)
        openPicker(pohang)
        compose.onNodeWithText("송도 날씨 그대로 보기").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { showHome.value = false }
        compose.runOnIdle { showHome.value = true }
        openPicker(pohang)
        pick(incheon)
        openPicker(incheon)
    }
}
