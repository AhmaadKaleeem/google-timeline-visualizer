package dev.mahlernim.timelinevisualizer.model

import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JourneyTest {
    private val seoul = GeoPoint(Instant.parse("2025-06-01T00:00:00Z"), 37.5665, 126.9780)
    private val bohol = GeoPoint(Instant.parse("2025-06-01T04:00:00Z"), 9.8500, 124.1435)

    @Test
    fun continuouslyInterpolatesALongFlight() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)
        val quarter = journey.positionAt(0.25f)
        val halfway = journey.positionAt(0.5f)
        val threeQuarters = journey.positionAt(0.75f)

        assertTrue(quarter.point.latitude < seoul.latitude)
        assertTrue(quarter.point.latitude > halfway.point.latitude)
        assertTrue(halfway.point.latitude > threeQuarters.point.latitude)
        assertTrue(threeQuarters.point.latitude > bohol.latitude)
        assertEquals(journey.totalDistanceKm / 2.0, halfway.distanceKm, 0.1)
        assertEquals(0.5, halfway.segmentFraction, 0.0001)
    }

    @Test
    fun densifiesLongLegsForSmoothRendering() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)

        assertTrue(journey.renderPath.size > 20)
        val largestStep = journey.renderPath.zipWithNext { a, b -> b.distanceKm - a.distanceKm }.max()
        assertTrue("Largest rendered step was $largestStep km", largestStep <= 75.1)
    }

    @Test
    fun movingHeadStaysInsideItsCameraViewport() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)
        val position = journey.positionAt(0.5f)
        val projected = WebMercator.project(position.point)
        val viewport = TimelinePainter().viewport(journey, 0.5f, 480, 480)

        assertTrue(projected.x in viewport.minX..viewport.maxX)
        assertTrue(projected.y in viewport.minY..viewport.maxY)
        assertEquals((viewport.minX + viewport.maxX) / 2.0, projected.x, 0.0001)
    }

    @Test
    fun interpolationTakesTheShortWayAcrossTheDateLine() {
        val west = seoul.copy(latitude = 10.0, longitude = 179.0)
        val east = bohol.copy(latitude = 10.0, longitude = -179.0)
        val halfway = Journey.from(listOf(west, east), 2025).positionAt(0.5f).point

        assertTrue("Halfway longitude was ${halfway.longitude}", kotlin.math.abs(halfway.longitude) > 179.5)
    }

    @Test
    fun monthRangeDefaultsCanBeNarrowed() {
        val timeline = Timeline(
            listOf(
                seoul.copy(instant = Instant.parse("2025-01-15T00:00:00Z")),
                seoul.copy(instant = Instant.parse("2025-06-15T00:00:00Z")),
                bohol.copy(instant = Instant.parse("2025-07-15T00:00:00Z")),
                bohol.copy(instant = Instant.parse("2025-12-15T00:00:00Z")),
            ),
        )

        assertEquals(4, timeline.forYear(2025).points.size)
        assertEquals(2, timeline.forRange(2025, 6, 7).points.size)
    }
}
