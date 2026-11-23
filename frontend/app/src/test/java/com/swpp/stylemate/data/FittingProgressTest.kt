package com.swpp.stylemate.data

import com.swpp.stylemate.data.tryon.FittingProgress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FittingProgressTest {
    @Test fun serverStagesBecomeStepsAndTimeLeft() {
        val waiting = FittingProgress.of(JSONObject().put("status", "queued").put("queue_position", 2).put("progress", 0.05))
        assertEquals(FittingProgress("waiting", 0.05f, "대기 중 · 앞선 요청 2개"), waiting)
        val running = FittingProgress.of(JSONObject().put("status", "running").put("progress", 0.525).put("expected_seconds", 40.0))
        assertEquals("generating", running.stage)
        assertEquals(0.525f, running.fraction, 0.001f)
        assertEquals("옷을 입히고 있어요 · 약 22초 남음", running.text)
        assertEquals("거의 다 됐어요 · 마무리하고 있어요",
            FittingProgress.of(JSONObject().put("status", "running").put("progress", 0.95)).text)
        assertEquals(FittingProgress("done", 1f, "완료됐어요"), FittingProgress.of(JSONObject().put("status", "completed")))
    }

    @Test fun olderServersWithoutProgressAreEstimatedFromElapsedTime() {
        val running = FittingProgress.of(JSONObject().put("status", "running").put("elapsed_seconds", 20.0))
        assertEquals(0.525f, running.fraction, 0.001f)  // 0.1 + 0.85 × 20 / 40
    }
}
