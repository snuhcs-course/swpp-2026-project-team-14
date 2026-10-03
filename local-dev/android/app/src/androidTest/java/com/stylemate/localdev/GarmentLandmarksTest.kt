package com.stylemate.localdev

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GarmentLandmarksTest {
    private fun response(pair: String = "[[0.2,0.3],[0.8,0.3]]", garment: String = "short_sleeve_top") =
        JSONObject("""{"garment":"$garment","elapsed_ms":350,"suggestions":{"shoulder_width":$pair}}""")

    @Test fun normalizedPointsRemainInUploadedViewportCoordinates() {
        val result = GarmentLandmarks.parse(response(), "short_sleeve_top")
        assertEquals(LandmarkPath(listOf(LandmarkPoint(.2f, .3f), LandmarkPoint(.8f, .3f))), result.suggestions["shoulder_width"])
        assertEquals(350L, result.elapsedMs)
    }

    @Test fun intermediatePointsArePreservedForSeamLength() {
        val result = GarmentLandmarks.parse(response("[[0.2,0.3],[0.5,0.7],[0.8,0.3]]"), "short_sleeve_top")
        assertEquals(3, result.suggestions.getValue("shoulder_width").points.size)
    }

    @Test fun cutoutPreviewPreservesViewportAndDoesNotRescaleCoordinates() {
        val frame = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val data = response().put("background_removed", true).put("preview_jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val result = GarmentLandmarks.parse(data, "short_sleeve_top")
        assertEquals(120, result.preview!!.width)
        assertEquals(160, result.preview.height)
        assertEquals(.2f, result.suggestions.getValue("shoulder_width").points.first().x)
        result.preview.recycle()
        frame.recycle()
    }

    @Test fun mismatchedClassAndMalformedCoordinatesAreRejected() {
        for (data in listOf(response(garment = "trousers"), response("[[-0.1,0.3],[0.8,0.3]]"),
            response("[[0.2,1.1],[0.8,0.3]]"), response("[[0.2,0.3]]"))) {
            assertTrue(runCatching { GarmentLandmarks.parse(data, "short_sleeve_top") }.isFailure)
        }
    }

    @Test fun localModelAcceptsAnAndroidEncodedFrame() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("landmarkServer") == "true")
        val frame = Bitmap.createBitmap(576, 768, Bitmap.Config.ARGB_8888)
        try {
            val result = GarmentLandmarks.detect(frame, "short_sleeve_top")
            assertTrue(result.elapsedMs >= 0)
            assertTrue(result.suggestions.keys.all { it in topMeasurementFields.map { field -> field.key } })
        } finally { frame.recycle() }
    }
}
