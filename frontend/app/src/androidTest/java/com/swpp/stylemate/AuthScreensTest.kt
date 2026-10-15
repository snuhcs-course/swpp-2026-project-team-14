// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.KeystoreSessionStore
import com.swpp.stylemate.data.AuthSession
import com.swpp.stylemate.data.AuthException
import com.swpp.stylemate.data.AuthRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.swpp.stylemate.ui.profile.AuthUiState
import com.swpp.stylemate.ui.profile.LoginDialog
import com.swpp.stylemate.ui.profile.RegistrationScreen
import com.swpp.stylemate.ui.theme.StyleMateTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AuthScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val node = if (name == "login") compose.onNodeWithText("StyleMate").onParent() else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(context.cacheDir, "auth-preview-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun djangoOffsetTimestampParsesOnAndroid() {
        val json = JSONObject("""{"token":"${"a".repeat(43)}","expires_at":"2026-11-15T20:24:21.123456+00:00","user":{"id":"b3c3a372-9c51-4e47-9506-52dc352ff003","username":"member_1","body_profile":null,"preferred_fits":["regular"],"preferred_styles":[],"favorite_colors":[]}}""")
        assertEquals("2026-11-15T20:24:21.123456Z", AuthSession.parse(json).expiresAt.toString())
    }

    @Test fun liveServerRegistrationAfterMismatchRestoresAndLogsInOnPhone() = runBlocking {
        val username = InstrumentationRegistry.getArguments().getString("authTestUsername")
            ?: throw org.junit.AssumptionViolatedException("Requires an isolated username and a running development server")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "auth-live-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val storage = KeystoreSessionStore(scoped)
        val repository = AuthRepository(BuildConfig.API_BASE_URL, storage)
        val password = UUID.randomUUID().toString()
        try {
            try { repository.authenticate(username, password, "different"); fail("Mismatched confirmation accepted") }
            catch (error: AuthException) { assertEquals("PASSWORD_MISMATCH", error.code) }
            repository.authenticate(username, password, password)
            assertEquals(username, repository.status.value.session!!.user.username)
            val reopened = AuthRepository(BuildConfig.API_BASE_URL, storage)
            reopened.restore()
            reopened.verify()
            assertEquals(username, reopened.status.value.session!!.user.username)
            reopened.logout()
            repository.authenticate(username, password)
            repository.verify()
            assertEquals(username, repository.status.value.session!!.user.username)
            repository.logout()
        } finally { storage.clear(); directory.delete() }
    }

    @Test fun loginMasksPasswordAndPreservesExactInputForSubmission() {
        var submitted: Pair<String, String>? = null
        compose.setContent { StyleMateTheme { LoginDialog(AuthUiState(ready = true), {}, {}, { id, pw -> submitted = id to pw }) } }
        capture("login")
        compose.onNodeWithText("아이디").performTextInput("member_1")
        compose.onNodeWithText("비밀번호").performTextInput(" password_123 ")
        compose.onNodeWithText("비밀번호").assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
        compose.onNodeWithText("표시").performClick()
        compose.onNodeWithText("숨기기").assertIsDisplayed()
        compose.onNodeWithText("비밀번호").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            action(layouts)
            assertEquals(" password_123 ", layouts.single().layoutInput.text.text)
        }
        compose.onNode(hasText("로그인") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("member_1" to " password_123 ", submitted) }
    }

    @Test fun busyLoginDisablesSubmissionAndRegistrationButShowsServerErrorAndCooldown() {
        var state by mutableStateOf(AuthUiState(ready = true, busy = true))
        compose.setContent { StyleMateTheme { LoginDialog(state, {}, {}, { _, _ -> fail("Duplicate submission") }) } }
        compose.onNodeWithText("로그인 중…").assertIsNotEnabled()
        compose.onNodeWithText("회원가입").assertIsNotEnabled()
        compose.runOnIdle { state = AuthUiState(ready = true, retrySeconds = 300, error = "로그인 시도가 잠시 제한됐어요.") }
        compose.onNodeWithText("300초 후 다시 시도할 수 있어요.").assertIsDisplayed()
        compose.onNode(hasText("로그인") and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun registrationHasOnlyRequiredCredentialsAndPasswordConfirmation() {
        var submitted: Triple<String, String, String>? = null
        compose.setContent { StyleMateTheme { RegistrationScreen(AuthUiState(ready = true), {}, { id, pw, confirm -> submitted = Triple(id, pw, confirm) }) } }
        capture("register")
        compose.onNodeWithText("아이디").performTextInput("member_1")
        compose.onNodeWithText("비밀번호").performScrollTo().performTextInput("password_123")
        compose.onNodeWithText("비밀번호 확인").performScrollTo().performTextInput("password_123")
        compose.onNodeWithText("이메일").assertDoesNotExist()
        compose.onNodeWithText("전화번호").assertDoesNotExist()
        compose.onNode(hasText("회원가입") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(Triple("member_1", "password_123", "password_123"), submitted) }
    }

    @Test fun keystorePersistsCiphertextAndRejectsTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "auth-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val store = KeystoreSessionStore(scoped)
        try {
            val token = "test-sensitive-session"
            store.write(token)
            assertEquals(token, KeystoreSessionStore(scoped).read())
            val file = File(directory, "auth-session.enc")
            val bytes = file.readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains(token))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertThrows(Exception::class.java) { store.read() }
            store.clear()
            assertNull(store.read())
        } finally { store.clear(); directory.delete() }
    }
}
