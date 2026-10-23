package com.swpp.stylemate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.swpp.stylemate.data.*
import com.swpp.stylemate.ui.StyleMateApp
import com.swpp.stylemate.ui.profile.AuthViewModel
import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import com.swpp.stylemate.ui.theme.StyleMateTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class AuthenticationFlowTest {
    @get:Rule val compose = createComposeRule()

    private fun authenticatedModel(expire: () -> Boolean = { false }): AuthViewModel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val code = if (expire()) 401 else if (request.url.encodedPath == "/api/auth/logout/") 204 else 200
            val user = AccountUser(ACCOUNT_ID, "member_1", null, StyleProfile()).json()
            val body = if (code == 204) "" else if (code == 401) "{\"error\":\"AUTHENTICATION_REQUIRED\"}"
                       else JSONObject().put("user", user).toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return AuthViewModel(authenticatedRepository(client))
    }

    @Test fun logoutReturnsToLoginAndRemovesServiceNavigation() {
        val auth = authenticatedModel()
        val profile = BodyProfileViewModel(FakeBodyAnalyzer(0)).apply { skipSetup() }
        compose.setContent { StyleMateTheme { StyleMateApp(profile, auth) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("마이프로필").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("마이프로필").performClick()
        compose.onNodeWithText("로그아웃").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("나에게 맞는 스타일의 시작").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("옷장").assertDoesNotExist()
        compose.onNodeWithText("코디북").assertDoesNotExist()
    }

    @Test fun rejectedSessionReturnsToLoginAndDisposesAccountScreens() {
        var reject = false
        val auth = authenticatedModel { reject }
        val profile = BodyProfileViewModel(FakeBodyAnalyzer(0)).apply { skipSetup() }
        compose.setContent { StyleMateTheme { StyleMateApp(profile, auth) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("마이프로필").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { reject = true; auth.refreshSession() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("나에게 맞는 스타일의 시작").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("마이프로필").assertDoesNotExist()
        compose.onNodeWithText("코디북").assertDoesNotExist()
    }
}
