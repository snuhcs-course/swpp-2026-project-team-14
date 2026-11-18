package com.swpp.stylemate.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StarRatingTest {
    @Test fun scoresBecomeHalfStepStars() {
        assertEquals(4.0, starsOf(80), 0.0)
        assertEquals(4.5, starsOf(86), 0.0)
        assertEquals(4.5, starsOf(93), 0.0)
        assertEquals(5.0, starsOf(96), 0.0)
        assertEquals(3.5, starsOf(70), 0.0)
        assertEquals(2.5, starsOf(50), 0.0)
    }

    @Test fun theScaleIsClampedAndNeverEmpty() {
        assertEquals(0.5, starsOf(0), 0.0)
        assertEquals(5.0, starsOf(140), 0.0)
        assertEquals("4.5", starsText(4.5))
    }
}
