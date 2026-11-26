// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.tryon

import androidx.compose.ui.geometry.Rect
import com.swpp.stylemate.ui.wardrobe.GarmentHangingShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DressOnBodyTest {
    // female_160_regular, measured from its photo.
    private val body = BodyAnchors(shoulderY = .1985f, shoulderL = .3281f, shoulderR = .6632f,
        waistY = .4352f, hipL = .3524f, hipR = .6424f, ankleY = .8681f, neckY = .15f)
    private val photo = Rect(0f, 0f, 600f, 900f)
    private val tee = GarmentHangingShape(aspectRatio = 1.1f, left = .15f, right = .85f, supportY = .1f)
    private val jacket = GarmentHangingShape(aspectRatio = .95f, left = .2f, right = .8f, supportY = .1f)
    private val trousers = GarmentHangingShape(aspectRatio = .42f, left = .1f, right = .9f, supportY = .02f)

    @Test fun clothesHangFromTheShouldersAndTheWaist() {
        val placed = dressOnBody(body, photo, mapOf("top" to tee, "outerwear" to jacket, "bottom" to trousers))
        val top = placed.getValue("top"); val outer = placed.getValue("outerwear"); val bottom = placed.getValue("bottom")
        val shoulderX = (body.shoulderL + body.shoulderR) / 2 * photo.width
        assertEquals("the top is centred on the shoulders", shoulderX, top.center.x, 1f)
        assertEquals("its shoulder points sit on the shoulder line", body.shoulderY * photo.height, top.top + tee.supportY * top.height, 1f)
        assertEquals("its shoulder points span the shoulders", (body.shoulderR - body.shoulderL) * photo.width,
            (tee.right - tee.left) * top.width, 1f)
        assertTrue("the jacket is worn over the top", outer.width > top.width)
        assertEquals("trousers start at the waist", body.waistY * photo.height, bottom.top + trousers.supportY * bottom.height, 1f)
        assertTrue("and stop at the ankles", bottom.bottom <= body.ankleY * photo.height * 1.05f)
    }

    @Test fun aOneLegHipReadingFallsBackUnderTheShoulders() {
        val crooked = body.copy(hipL = .36f, hipR = .40f)
        val bottom = dressOnBody(crooked, photo, mapOf("bottom" to trousers)).getValue("bottom")
        val shoulderX = (body.shoulderL + body.shoulderR) / 2 * photo.width
        assertEquals(shoulderX, bottom.center.x, 1f)
        // Not the 4 % sliver that was read: about the usual hip width (a little less when the ankles cap the length).
        assertTrue(bottom.width * (trousers.right - trousers.left) > (body.shoulderR - body.shoulderL) * photo.width * .55f)
    }

    @Test fun spreadSleevesAndHighCollarsStayOnTheBody() {
        val puffer = GarmentHangingShape(aspectRatio = 1.2f, left = .3f, right = .7f, supportY = .3f)
        val jacket = dressOnBody(body.copy(neckY = .2f), photo, mapOf("outerwear" to puffer)).getValue("outerwear")
        val shoulders = (body.shoulderR - body.shoulderL) * photo.width
        assertTrue("no wider than the body allows", jacket.width <= shoulders * 1.1f * MAX_SPREAD + .5f)
        assertTrue("the collar does not cover the chin", jacket.top >= .2f * photo.height - .012f * photo.height - .5f)
    }
}
