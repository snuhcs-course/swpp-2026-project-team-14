package com.stylemate.localdev

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CaptureFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = CaptureFiles(context)

    @Test fun cameraUriCanStoreAndReadPhotoThenDeleteIt() {
        val name = files.create()
        try {
            val uri = files.uri(name)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.captures", uri.authority)
            assertFalse(files.exists(name))
            val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            assertTrue(files.exists(name))
            val preview = files.preview(name)!!
            assertTrue(maxOf(preview.width, preview.height) <= 1200)
            preview.recycle()
        } finally { files.delete(name) }
        assertFalse(files.exists(name))
    }

    @Test fun cancelledReplacementDoesNotDeletePreviousPhoto() {
        val previous = files.create()
        val replacement = files.create()
        try {
            context.contentResolver.openOutputStream(files.uri(previous))!!.use { it.write(byteArrayOf(1)) }
            files.delete(replacement)
            assertTrue(files.exists(previous))
            assertFalse(files.exists(replacement))
        } finally { files.delete(previous); files.delete(replacement) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun capturePathsRejectTraversal() { files.uri("../preferences.xml") }
}
