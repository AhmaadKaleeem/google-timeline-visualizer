package dev.mahlernim.timelinevisualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineViewTest {
    @Test
    fun previewSizePreservesEveryExportAspect() {
        listOf(1f, 9f / 16f, 16f / 9f).forEach { aspect ->
            val (width, height) = TimelineView.previewSize(1080, 1380, aspect)

            assertTrue(width <= 1080)
            assertTrue(height <= 1380)
            assertEquals(aspect, width.toFloat() / height, 0.002f)
        }
    }

    @Test
    fun landscapePreviewDoesNotDistortWhenItIsShorterThanTheOldMinimum() {
        val (width, height) = TimelineView.previewSize(1080, 1380, 16f / 9f)

        assertEquals(1080, width)
        assertEquals(608, height)
        assertEquals(16f / 9f, width.toFloat() / height, 0.002f)
    }
}
