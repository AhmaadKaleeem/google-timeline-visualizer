package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Duration
import java.time.Instant

enum class RouteSource {
    DETAILED,
    SEMANTIC_PATH,
    SEMANTIC_ENDPOINTS,
    GAP,
}

data class RouteSpan(
    val start: Instant,
    val end: Instant,
    val source: RouteSource,
    val points: List<GeoPoint>,
    val transitionReason: String? = null,
) {
    init {
        require(end >= start)
        require(source == RouteSource.GAP || points.isNotEmpty())
        require(source != RouteSource.GAP || points.isEmpty())
    }
}

/**
 * Builds a source-aware route without concatenating overlapping detailed and semantic points.
 *
 * The detailed input is expected to have passed the active conservative observation filter.
 * Semantic segment identity is not available from the v2 parser yet, so this first contract
 * uses semantic path points while preserving gaps between detailed coverage islands.
 */
object JournalRouteFusion {
    val DEFAULT_DISCONTINUITY: Duration = Duration.ofMinutes(30)

    fun fuse(
        semanticPoints: List<GeoPoint>,
        detailedPoints: List<GeoPoint>,
        discontinuity: Duration = DEFAULT_DISCONTINUITY,
    ): List<RouteSpan> {
        require(!discontinuity.isNegative && !discontinuity.isZero)

        val detailedIslands = splitDetailedIslands(resolveDetailedConflicts(detailedPoints), discontinuity)
        if (detailedIslands.isEmpty()) return semanticSpan(semanticPoints)?.let(::listOf).orEmpty()

        val semanticOutsideDetailed = semanticPoints
            .asSequence()
            .sortedBy(GeoPoint::instant)
            .distinctBy(::pointKey)
            .filterNot { point -> detailedIslands.any { island -> point.instant in island.interval } }
            .toList()

        val spans = mutableListOf<RouteSpan>()
        var semanticIndex = 0
        detailedIslands.forEach { island ->
            val before = mutableListOf<GeoPoint>()
            while (
                semanticIndex < semanticOutsideDetailed.size &&
                semanticOutsideDetailed[semanticIndex].instant < island.start
            ) {
                before += semanticOutsideDetailed[semanticIndex++]
            }
            semanticSpan(before)?.let(spans::add)
            spans += RouteSpan(
                start = island.start,
                end = island.end,
                source = RouteSource.DETAILED,
                points = island.points,
            )
        }
        semanticSpan(semanticOutsideDetailed.drop(semanticIndex))?.let(spans::add)

        return insertDetailedGaps(spans)
    }

    private fun resolveDetailedConflicts(points: List<GeoPoint>): List<GeoPoint> = points
        .sortedBy(GeoPoint::instant)
        .groupBy(GeoPoint::instant)
        .mapNotNull { (_, candidates) ->
            val coordinates = candidates.distinctBy { it.latitude.toBits() to it.longitude.toBits() }
            coordinates.singleOrNull()
        }

    private fun splitDetailedIslands(
        points: List<GeoPoint>,
        discontinuity: Duration,
    ): List<DetailedIsland> {
        if (points.isEmpty()) return emptyList()
        val islands = mutableListOf<MutableList<GeoPoint>>()
        points.forEach { point ->
            val current = islands.lastOrNull()
            if (
                current == null ||
                Duration.between(current.last().instant, point.instant) > discontinuity
            ) {
                islands += mutableListOf(point)
            } else {
                current += point
            }
        }
        return islands.map(::DetailedIsland)
    }

    private fun semanticSpan(points: List<GeoPoint>): RouteSpan? {
        if (points.isEmpty()) return null
        val ordered = points.sortedBy(GeoPoint::instant).distinctBy(::pointKey)
        return RouteSpan(
            start = ordered.first().instant,
            end = ordered.last().instant,
            source = RouteSource.SEMANTIC_PATH,
            points = ordered,
        )
    }

    private fun insertDetailedGaps(spans: List<RouteSpan>): List<RouteSpan> {
        if (spans.size < 2) return spans
        val result = mutableListOf<RouteSpan>()
        spans.forEach { next ->
            val previous = result.lastOrNull()
            if (previous?.source == RouteSource.DETAILED && next.source == RouteSource.DETAILED) {
                result += RouteSpan(
                    start = previous.end,
                    end = next.start,
                    source = RouteSource.GAP,
                    points = emptyList(),
                    transitionReason = "No supported route observations",
                )
            }
            result += next
        }
        return result
    }

    private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
        point.instant.toEpochMilli(),
        point.latitude.toBits(),
        point.longitude.toBits(),
    )

    private data class DetailedIsland(val points: List<GeoPoint>) {
        val start: Instant = points.first().instant
        val end: Instant = points.last().instant
        val interval: ClosedRange<Instant> = start..end
    }
}
