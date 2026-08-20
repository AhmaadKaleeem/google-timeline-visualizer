package dev.mahlernim.timelinevisualizer.export

import dev.mahlernim.timelinevisualizer.render.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class Mp4ExporterTest {
    @Test
    fun overviewImageMatchesTheSelectedAspect() {
        assertEquals(1080, Mp4Exporter.overviewWidth(VideoQuality.STANDARD))
        assertEquals(1080, Mp4Exporter.overviewHeight(VideoQuality.STANDARD))

        assertEquals(608, Mp4Exporter.overviewWidth(VideoQuality.PORTRAIT))
        assertEquals(1080, Mp4Exporter.overviewHeight(VideoQuality.PORTRAIT))

        assertEquals(1080, Mp4Exporter.overviewWidth(VideoQuality.LANDSCAPE))
        assertEquals(608, Mp4Exporter.overviewHeight(VideoQuality.LANDSCAPE))
    }
}
