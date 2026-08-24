package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalRouteFusionTest {
    @Test
    fun detailedGeometryReplacesCompleteSemanticOverlap() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(0, 0.0), point(10, 10.0), point(20, 20.0)),
            detailedPoints = listOf(point(0, 1.0), point(5, 2.0), point(20, 3.0)),
        )

        assertEquals(listOf(RouteSource.DETAILED), result.map(RouteSpan::source))
        assertEquals(listOf(1.0, 2.0, 3.0), result.single().points.map(GeoPoint::latitude))
    }

    @Test
    fun semanticGeometryFillsBeforeAndAfterDetailedIslandOnce() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(0, 0.0), point(10, 10.0), point(20, 20.0), point(30, 30.0)),
            detailedPoints = listOf(point(10, 11.0), point(15, 12.0), point(20, 13.0)),
        )

        assertEquals(
            listOf(RouteSource.SEMANTIC_PATH, RouteSource.DETAILED, RouteSource.SEMANTIC_PATH),
            result.map(RouteSpan::source),
        )
        assertEquals(listOf(0.0, 11.0, 12.0, 13.0, 30.0), result.flatMap(RouteSpan::points).map(GeoPoint::latitude))
    }

    @Test
    fun semanticTimelineIsTheFallbackWhenNoDetailedObservationsExist() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(0, 0.0), point(60, 1.0)),
            detailedPoints = emptyList(),
        )

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), result.map(RouteSpan::source))
    }

    @Test
    fun detailedOnlyDiscontinuityRemainsAnExplicitGap() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = emptyList(),
            detailedPoints = listOf(point(0, 0.0), point(10, 1.0), point(50, 2.0), point(55, 3.0)),
        )

        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.GAP, RouteSource.DETAILED),
            result.map(RouteSpan::source),
        )
        assertEquals(0, result[1].points.size)
    }

    @Test
    fun ambiguousDetailedTimestampIsRejectedInsteadOfCreatingBacktracking() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(10, 8.0)),
            detailedPoints = listOf(point(10, 1.0), point(10, 99.0)),
        )

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), result.map(RouteSpan::source))
        assertEquals(8.0, result.single().points.single().latitude, 0.0)
    }

    @Test
    fun multipleDetailedIslandsSplitOneSemanticPathWithoutLosingFragments() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = (0L..60L step 10L).map { minute -> point(minute, minute.toDouble()) },
            detailedPoints = listOf(
                point(10, 101.0),
                point(50, 105.0),
            ),
            discontinuity = java.time.Duration.ofMinutes(30),
        )

        assertEquals(
            listOf(
                RouteSource.SEMANTIC_PATH,
                RouteSource.DETAILED,
                RouteSource.SEMANTIC_PATH,
                RouteSource.DETAILED,
                RouteSource.SEMANTIC_PATH,
            ),
            result.map(RouteSpan::source),
        )
        assertEquals(
            listOf(0.0, 101.0, 20.0, 30.0, 40.0, 105.0, 60.0),
            result.flatMap(RouteSpan::points).map(GeoPoint::latitude),
        )
    }

    private fun point(minutes: Long, latitude: Double): GeoPoint = GeoPoint(
        instant = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minutes * 60),
        latitude = latitude,
        longitude = latitude,
    )
}
