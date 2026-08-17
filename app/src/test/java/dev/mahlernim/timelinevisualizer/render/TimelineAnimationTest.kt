package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineAnimationTest {
    @Test
    fun selectedDurationIsFollowedByOneAndAHalfSecondEnding() {
        assertEquals(31.5f, TimelineAnimation.totalDurationSeconds(30), 0.001f)
        assertEquals(76.5f, TimelineAnimation.totalDurationSeconds(75), 0.001f)
    }

    @Test
    fun endingZoomCompletesBeforeTheFinalHalfSecondHold() {
        val transitionEnd = TimelineAnimation.frameAtElapsedSeconds(31f, 30)
        val heldFrame = TimelineAnimation.frameAtElapsedSeconds(31.4f, 30)

        assertEquals(1f, transitionEnd.journeyProgress, 0.001f)
        assertEquals(1f, transitionEnd.outroProgress, 0.001f)
        assertEquals(1f, heldFrame.outroProgress, 0.001f)
    }
}
