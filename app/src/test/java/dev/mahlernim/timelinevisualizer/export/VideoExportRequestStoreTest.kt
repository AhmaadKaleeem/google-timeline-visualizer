package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoExportRequestStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = VideoExportRequestStore(context).also(VideoExportRequestStore::clear)

    @After
    fun tearDown() = store.clear()

    @Test
    fun restoresEverythingNeededToRestartVideoCreation() {
        val points = listOf(
            GeoPoint(Instant.parse("2026-01-02T03:04:05Z"), 37.5665, 126.9780),
            GeoPoint(Instant.parse("2026-06-07T08:09:10Z"), 9.6500, 123.8500),
        )
        val request = VideoExportRequest(
            outputUri = "content://documents/timeline.mp4",
            journey = Journey.from(points, 2026),
            title = "2026 Mina's Timeline",
            durationSeconds = 60,
            startMonth = 1,
            endMonth = 6,
        )

        store.save(request)
        val restored = VideoExportRequestStore(context).load()!!

        assertEquals(request.outputUri, restored.outputUri)
        assertEquals(request.title, restored.title)
        assertEquals(request.durationSeconds, restored.durationSeconds)
        assertEquals(request.startMonth, restored.startMonth)
        assertEquals(request.endMonth, restored.endMonth)
        assertEquals(request.journey.year, restored.journey.year)
        assertEquals(request.journey.points, restored.journey.points)
    }

    @Test
    fun clearRemovesPendingRestartData() {
        val request = VideoExportRequest(
            outputUri = "content://documents/timeline.mp4",
            journey = Journey.from(emptyList(), 2026),
            title = "Timeline",
            durationSeconds = 30,
            startMonth = 1,
            endMonth = 12,
        )
        store.save(request)

        store.clear()

        assertNull(store.load())
    }
}
