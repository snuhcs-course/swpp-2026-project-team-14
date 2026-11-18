package com.swpp.stylemate.wardrobe

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.WardrobeItem
import com.swpp.stylemate.ui.wardrobe.WardrobeScreen
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/** Captures the real Compose UI using isolated sample records, without account/production writes. */
class WardrobeDeletePreviewTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose idleness does not include the system dialog window's fade animation.
        android.os.SystemClock.sleep(1_000)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "delete-review")
        directory.mkdirs()
        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val output = "/sdcard/Download/stylemate-delete-review"
        for (command in listOf("mkdir -p $output", "cp ${file.absolutePath} $output/${file.name}")) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
    }

    @Test fun captureConfirmedDeletion() {
        val items = mutableStateOf(listOf(
            WardrobeItem("shirt", "화이트 티셔츠", "top", style = "캐주얼"),
            WardrobeItem("jeans", "데님 팬츠", "bottom", style = "캐주얼"),
        ))
        compose.setContent { StyleMateTheme {
            WardrobeScreen(items.value, null, false, {}, onDelete = { garment ->
                items.value = items.value.filter { it.id != garment.id }
            })
        } }
        compose.onNodeWithText("총 2벌").assertIsDisplayed()
        capture("01-wardrobe")
        compose.onNodeWithContentDescription("화이트 티셔츠 삭제").performClick()
        compose.onNodeWithText("'화이트 티셔츠'을(를) 옷장에서 삭제할까요?").assertIsDisplayed()
        capture("02-confirmation")
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithContentDescription("화이트 티셔츠 삭제").assertDoesNotExist()
        compose.onNodeWithText("총 1벌").assertIsDisplayed()
        capture("03-after-delete")
    }
}
