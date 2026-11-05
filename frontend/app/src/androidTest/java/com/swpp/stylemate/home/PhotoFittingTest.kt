package com.swpp.stylemate.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.app.NotificationManager
import android.provider.MediaStore
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.ACCOUNT_ID
import com.swpp.stylemate.authenticatedRepository
import com.swpp.stylemate.data.tryon.VirtualFittingModels
import com.swpp.stylemate.data.tryon.FittingRepository
import com.swpp.stylemate.data.tryon.FittingNotificationSettings
import com.swpp.stylemate.data.tryon.FittingNotifications
import com.swpp.stylemate.ui.home.CodiBookScreen
import com.swpp.stylemate.ui.home.CodiBookState
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.tryon.PhotoFittingContent
import com.swpp.stylemate.ui.tryon.FittingDialog
import com.swpp.stylemate.ui.tryon.FittingNotificationToggle
import com.swpp.stylemate.ui.tryon.saveFittingImage
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class PhotoFittingTest {
    private val account = checkNotNull(authenticatedRepository().account())
    @get:Rule val compose = createComposeRule()

    @Test fun photoSelectionRequiresTheConsentAction() {
        var selections = 0
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, null, null, { selections++ }, {}, {}, {}, modifier = Modifier.fillMaxSize())
        } }
        compose.onNodeWithText("서버의 원본 사진은 처리 후 바로 삭제됩니다.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("생성한 결과가 있으면 바로 보여드려요.").assertDoesNotExist()
        assertEquals(0, selections)
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun completionNotificationBellIsAnAccessibleOnOffSwitch() {
        var changes = 0
        compose.setContent { StyleMateTheme {
            var enabled by remember { mutableStateOf(false) }
            FittingNotificationToggle(enabled, { enabled = it; changes++ })
        } }
        val bell = compose.onNodeWithContentDescription("피팅 완료 알림")
        bell.assertIsOff().performClick()
        bell.assertIsOn().performClick()
        bell.assertIsOff()
        compose.runOnIdle { assertEquals(2, changes) }
    }

    @Test fun notificationBellKeepsItsSettingAcrossDifferentOutfitsAndReopenedDialogs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(FittingNotifications.allowed(context))
        val directory = File(context.cacheDir, "fitting-bell-${UUID.randomUUID()}")
        val first = Outfit("first-outfit", "first", null, emptyList(), emptyList(), true, "", null, "")
        val second = first.copy(id = "second-outfit", key = "second")
        try {
            compose.setContent { StyleMateTheme {
                var selected by remember { mutableStateOf<Outfit?>(null) }
                androidx.compose.foundation.layout.Column {
                    Button(onClick = { selected = first }) { Text("첫 코디 피팅") }
                    Button(onClick = { selected = second }) { Text("다른 코디 피팅") }
                }
                selected?.let { outfit ->
                    val repository = remember(outfit) { FittingRepository(LocalUserStore.inDirectory(directory, ACCOUNT_ID), account) }
                    FittingDialog(outfit, repository, account, onClose = { selected = null })
                }
            } }
            compose.onNodeWithText("첫 코디 피팅").performClick()
            compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertIsDisplayed()
            val bell = compose.onNodeWithContentDescription("피팅 완료 알림")
            bell.assertIsOff().performClick()
            compose.waitUntil(5_000) {
                runBlocking { FittingRepository(LocalUserStore.inDirectory(directory, ACCOUNT_ID), account).notificationSettings().enabled }
            }
            bell.assertIsOn()
            compose.onNodeWithText("닫기").performClick()
            compose.onNodeWithText("다른 코디 피팅").performClick()
            compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertIsDisplayed()
            bell.assertIsOn().performClick()
            compose.waitUntil(5_000) {
                !runBlocking { FittingRepository(LocalUserStore.inDirectory(directory, ACCOUNT_ID), account).notificationSettings().enabled }
            }
            bell.assertIsOff()
            compose.onNodeWithText("닫기").performClick()
            compose.onNodeWithText("첫 코디 피팅").performClick()
            compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertIsDisplayed()
            bell.assertIsOff()
            compose.onNodeWithText("피팅 생성 완료 시 알림을 받을까요?").assertDoesNotExist()
            compose.onNodeWithText("다음부터 이 팝업창을 보이지 않습니다").assertDoesNotExist()
        } finally { directory.deleteRecursively() }
    }

    @Test fun completedNotificationHonorsTheSavedOnOffSetting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(FittingNotifications.allowed(context))
        val owner = UUID.randomUUID().toString()
        val key = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val store = LocalUserStore.forContext(context, owner)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            store.put(FittingNotificationSettings.storageKey(owner), FittingNotificationSettings(true).json())
            FittingNotifications.finished(context, key, owner)
            compose.waitUntil(5_000) { manager.activeNotifications.any { it.id == key.hashCode() } }
            manager.cancel(key.hashCode())
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == key.hashCode() } }
            store.put(FittingNotificationSettings.storageKey(owner), FittingNotificationSettings(false).json())
            FittingNotifications.finished(context, key, owner)
            android.os.SystemClock.sleep(200)
            assertFalse(manager.activeNotifications.any { it.id == key.hashCode() })
        } finally {
            manager.cancel(key.hashCode())
            File(context.filesDir, "local-users/$owner").deleteRecursively()
        }
    }

    @Test fun sourcePreparationShowsOnlyAShortPreparationState() {
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(true, null, null, {}, {}, {}, {}, modifier = Modifier.fillMaxSize())
        } }
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithText("사진을 준비하고 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertDoesNotExist()
        compose.onNodeWithText("내 옷으로 피팅 만들기").assertDoesNotExist()
        compose.onNodeWithText("저장").assertDoesNotExist()
        compose.onNodeWithText("확인").assertDoesNotExist()
    }

    @Test fun backgroundGenerationShowsADismissibleNoticeWithoutASpinnerOrDuplicateRequest() {
        var closed = 0
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, null, null, {}, {}, {}, { closed++ },
                pending = true, modifier = Modifier.fillMaxSize())
        } }
        compose.onNodeWithText("가상 피팅을 만드는 중입니다.").assertIsDisplayed()
        compose.onNodeWithText("이 창을 닫고 다른 기능을 이용하셔도 됩니다.", substring = true).assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertDoesNotExist()
        compose.onNodeWithText("내 체형과 비슷한\n가상 모델에게 입히기").assertDoesNotExist()
        compose.onNodeWithText("확인").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun completionShowsTheImageWithoutAnotherGenerateButton() {
        val image = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        var saves = 0
        var confirmations = 0
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, image, null, {}, {}, { saves++ }, { confirmations++ }, modifier = Modifier.fillMaxSize())
        } }
        compose.onNodeWithContentDescription("생성된 피팅 이미지").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertDoesNotExist()
        val save = compose.onNodeWithText("저장").assertIsDisplayed()
        val confirm = compose.onNodeWithText("확인").assertIsDisplayed()
        val photo = compose.onNodeWithContentDescription("생성된 피팅 이미지").fetchSemanticsNode().boundsInRoot
        assertTrue(save.fetchSemanticsNode().boundsInRoot.left < confirm.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(save.fetchSemanticsNode().boundsInRoot.top >= photo.bottom)
        save.performClick()
        confirm.performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, confirmations)
        }
    }

    @Test fun savedResultDisablesDuplicateSaving() {
        val image = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, image, null, {}, {}, {}, {}, saved = true, modifier = Modifier.fillMaxSize())
        } }
        compose.onNodeWithText("저장").assertIsNotEnabled()
        compose.onNodeWithText("확인").assertIsDisplayed()
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun savedImageIsPublishedInTheGalleryWithTheOriginalPixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val uri = saveFittingImage(context, image)
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING,
                MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("Pictures/StyleMate/", it.getString(1))
            }
            context.contentResolver.openInputStream(uri)!!.use { input ->
                val restored = BitmapFactory.decodeStream(input)!!
                try {
                    assertEquals(image.width, restored.width)
                    assertEquals(image.height, restored.height)
                    assertEquals(Color.MAGENTA, restored.getPixel(16, 24))
                } finally { restored.recycle() }
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
            image.recycle()
        }
    }

    @Test fun failureLetsTheUserChooseAnotherPhoto() {
        var selections = 0
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, null, "피팅 서버에 연결하지 못했어요.",
                { selections++ }, {}, {}, {}, modifier = Modifier.fillMaxSize())
        } }
        compose.onNodeWithText("피팅 서버에 연결하지 못했어요.").assertIsDisplayed()
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun confirmingAndReopeningRestoresTheImageWithoutPhotoSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "fitting-reopen-${UUID.randomUUID()}")
        val outfit = Outfit("cached-outfit", "cached", null, emptyList(), emptyList(), true, "", null, "")
        val image = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            val repository = FittingRepository(LocalUserStore.inDirectory(directory, ACCOUNT_ID), account)
            val bytes = ByteArrayOutputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
            repository.saveResult(outfit, bytes)
            compose.setContent { StyleMateTheme {
                var visible by remember { mutableStateOf(false) }
                Button(onClick = { visible = true }) { Text("피팅 열기") }
                if (visible) FittingDialog(outfit, repository, account, onClose = { visible = false })
            } }
            repeat(2) {
                compose.onNodeWithText("피팅 열기").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("생성된 피팅 이미지").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("생성된 피팅 이미지").assertIsDisplayed()
                compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertDoesNotExist()
                compose.onNodeWithText("확인").performClick()
                compose.onNodeWithContentDescription("생성된 피팅 이미지").assertDoesNotExist()
            }
        } finally {
            image.recycle()
            directory.deleteRecursively()
        }
    }

    @Test fun codiBookButtonUsesTheSelectedOutfit() {
        val saved = Outfit("saved", "key", null,
            listOf(OutfitGarment("top", "top", "티셔츠", "", ""),
                OutfitGarment("bottom", "bottom", "청바지", "", "")),
            emptyList(), true, "저장한 코디", null, "2026-10-31")
        var selected: Outfit? = null
        compose.setContent { StyleMateTheme {
            CodiBookScreen(CodiBookState(outfits = listOf(saved)), {}, {},
                photo = { _, _ -> }, onTryOn = { selected = it })
        } }
        compose.onNodeWithText("가상 피팅").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(saved, selected) }
    }

    @Test fun bothSourceChoicesAreSeparateActionsWithGalleryOnTheLeft() {
        var gallery = 0
        var virtual = 0
        compose.setContent { StyleMateTheme {
            PhotoFittingContent(false, null, null, { gallery++ }, { virtual++ }, {}, {}, modifier = Modifier.fillMaxSize())
        } }
        val left = compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertIsDisplayed()
        val right = compose.onNodeWithText("내 체형과 비슷한\n가상 모델에게 입히기").assertIsDisplayed()
        assertTrue(left.fetchSemanticsNode().boundsInRoot.left < right.fetchSemanticsNode().boundsInRoot.left)
        right.performClick()
        compose.runOnIdle { assertEquals(0, gallery); assertEquals(1, virtual) }
        left.performClick()
        compose.runOnIdle { assertEquals(1, gallery) }
    }

    @Test fun allTwentyFourModelAssetsDecodeAtTheFittingResolution() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(24, VirtualFittingModels.all.size)
        for (model in VirtualFittingModels.all) {
            context.assets.open(model.assetPath).use { input ->
                val image = checkNotNull(BitmapFactory.decodeStream(input))
                try { assertEquals(576, image.width); assertEquals(864, image.height) }
                finally { image.recycle() }
            }
        }
    }

    @Test fun missingBodyInformationOnlyRequiresBasicInputsWithoutAPersonalPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "fitting-body-input-${UUID.randomUUID()}")
        val outfit = Outfit("model-input", "key", null, emptyList(), emptyList(), true, "", null, "")
        try {
            val repository = FittingRepository(LocalUserStore.inDirectory(directory, ACCOUNT_ID), account)
            compose.setContent { StyleMateTheme { FittingDialog(outfit, repository, account, {}) } }
            compose.onNodeWithText("내 체형과 비슷한\n가상 모델에게 입히기").performClick()
            compose.onNodeWithText("이 모델에게 입히기").assertIsNotEnabled()
            compose.onNodeWithText("남성").performClick()
            compose.onNodeWithText("키 (cm)").performTextInput("170")
            compose.onNodeWithText("몸무게 (kg)").performTextInput("85")
            compose.onNodeWithText("비슷한 가상 모델: 남성 170cm · 78kg 기준").assertIsDisplayed()
            compose.onNodeWithText("이 모델에게 입히기").assertIsEnabled()
            compose.onNodeWithText("사진 선택 방식으로 돌아가기").performScrollTo().performClick()
            compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertIsDisplayed()
        } finally { directory.deleteRecursively() }
    }

}
