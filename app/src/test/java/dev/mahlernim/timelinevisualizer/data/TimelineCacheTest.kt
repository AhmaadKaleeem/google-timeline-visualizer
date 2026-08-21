package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.io.File
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cache = TimelineCache(context).also(TimelineCache::clear)
    private val source = File(context.cacheDir, "timeline-cache-source.json")

    @After
    fun tearDown() {
        cache.clear()
        source.delete()
    }

    @Test
    fun roundTripsSemanticAndRawPointsWithoutLosingTimestampPrecision() {
        val semantic = GeoPoint(Instant.parse("2025-01-02T03:04:05.123456789Z"), 35.1234, 126.5678)
        val raw = RawSignalPoint(
            GeoPoint(Instant.parse("2025-01-02T03:05:06.987654321Z"), 36.1234, 127.5678),
            accuracyMeters = 12.5,
        )
        val parsed = ParsedTimeline(Timeline(listOf(semantic)), listOf(raw))
        val fingerprint = fingerprint(size = 1_024L, lastModified = 2_048L)

        cache.store(fingerprint, parsed)

        assertEquals(parsed, cache.load(fingerprint))
    }

    @Test
    fun preservesRawOnlyImports() {
        val raw = RawSignalPoint(
            GeoPoint(Instant.parse("2024-01-01T00:00:00Z"), 35.0, 126.0),
            accuracyMeters = 20.0,
        )
        val parsed = ParsedTimeline(timeline = null, rawSignals = listOf(raw))
        val fingerprint = fingerprint(size = 2_048L, lastModified = 4_096L)

        cache.store(fingerprint, parsed)

        assertEquals(parsed, cache.load(fingerprint))
    }

    @Test
    fun replacingAnEntryWithTheSameFingerprintKeepsTheNewestData() {
        val fingerprint = fingerprint(size = 2_048L, lastModified = 4_096L)
        val first = ParsedTimeline(
            Timeline(listOf(GeoPoint(Instant.EPOCH, 35.0, 126.0))),
            rawSignals = emptyList(),
        )
        val second = ParsedTimeline(
            Timeline(listOf(GeoPoint(Instant.EPOCH, 45.0, 126.0))),
            rawSignals = emptyList(),
        )

        cache.store(fingerprint, first)
        cache.store(fingerprint, second)

        assertEquals(second, cache.load(fingerprint))
    }

    @Test
    fun rejectsChangedSourceMetadataWithoutDestroyingMatchingEntry() {
        val parsed = ParsedTimeline(
            Timeline(listOf(GeoPoint(Instant.EPOCH, 35.0, 126.0))),
            rawSignals = emptyList(),
        )
        val original = fingerprint(size = 100L, lastModified = 200L)
        cache.store(original, parsed)

        assertNull(cache.load(original.copy(size = 101L)))
        assertNull(cache.load(original.copy(lastModified = 201L)))
        assertNull(cache.load(original.copy(uri = "content://example/other.json")))
        assertEquals(parsed, cache.load(original))
    }

    @Test
    fun corruptEntryIsRemovedAndTreatedAsAMiss() {
        File(context.cacheDir, "timeline-v1.bin").writeBytes(byteArrayOf(1, 2, 3, 4))

        assertNull(cache.load(fingerprint(size = 4L, lastModified = 1L)))
        assertTrue(!File(context.cacheDir, "timeline-v1.bin").exists())
    }

    @Test
    fun rememberedLoadUsesCacheWhileManualLoadRefreshesIt() {
        source.writeText(timelineJson(35.0))
        val originalModified = source.lastModified()
        val loader = CachedTimelineLoader(context)
        val uri = Uri.fromFile(source)

        val first = loader.load(uri, useCache = false)
        source.writeText(timelineJson(45.0))
        assertTrue(source.setLastModified(originalModified))

        val remembered = loader.load(uri, useCache = true)
        val refreshed = loader.load(uri, useCache = false)

        assertEquals(35.0, first.timeline!!.points.single().latitude, 0.0)
        assertEquals(35.0, remembered.timeline!!.points.single().latitude, 0.0)
        assertEquals(45.0, refreshed.timeline!!.points.single().latitude, 0.0)
        assertEquals(45.0, loader.load(uri, useCache = true).timeline!!.points.single().latitude, 0.0)
    }

    @Test
    fun changedFileMetadataFallsBackToJsonAndReplacesCache() {
        source.writeText(timelineJson(35.0))
        val loader = CachedTimelineLoader(context)
        val uri = Uri.fromFile(source)
        loader.load(uri, useCache = false)

        val previousModified = source.lastModified()
        source.writeText(timelineJson(45.0))
        assertTrue(source.setLastModified(previousModified + 5_000L))

        val refreshed = loader.load(uri, useCache = true)

        assertEquals(45.0, refreshed.timeline!!.points.single().latitude, 0.0)
        assertEquals(45.0, loader.load(uri, useCache = true).timeline!!.points.single().latitude, 0.0)
    }

    private fun fingerprint(size: Long, lastModified: Long) = TimelineSourceFingerprint(
        uri = "content://example/timeline.json",
        size = size,
        lastModified = lastModified,
    )

    private fun timelineJson(latitude: Double): String =
        """{"semanticSegments":[{"startTime":"2025-01-01T00:00:00Z","visit":{"topCandidate":{"placeLocation":"$latitude,126.0"}}}]}"""
}
