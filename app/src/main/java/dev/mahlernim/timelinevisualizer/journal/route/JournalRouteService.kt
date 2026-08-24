package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.data.RawSignalPoint
import dev.mahlernim.timelinevisualizer.data.RawSignalProcessor
import dev.mahlernim.timelinevisualizer.journal.ActiveSemanticSegment
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Duration
import java.time.Instant

data class JournalRoute(
    /** Compatibility projection for consumers that cannot represent route gaps yet. */
    val timeline: Timeline,
    /** Canonical source-aware topology for preview, distance, and export. */
    val spans: List<RouteSpan>,
    val detailedInputCount: Int,
    val detailedUsableCount: Int,
    val semanticUsableCount: Int,
)

/** Reconstructs the active Journal projection and applies detailed-first route fusion. */
class JournalRouteService(
    private val repository: JournalRepository,
) {
    suspend fun route(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        maximumAccuracyMeters: Double? = RawSignalProcessor.DEFAULT_MAXIMUM_ACCURACY_METERS,
        discontinuity: Duration = JournalRouteFusion.DEFAULT_DISCONTINUITY,
    ): JournalRoute {
        require(endExclusive > start) { "The route range must not be empty" }
        val startMillis = start.toEpochMilli()
        val endMillis = endExclusive.toEpochMilli()
        val detailedRows = repository.activeDetailedObservations(journalId, startMillis, endMillis)
        val detailed = RawSignalProcessor.process(
            source = detailedRows.map { row ->
                RawSignalPoint(
                    point = GeoPoint(
                        instant = Instant.ofEpochMilli(row.instantEpochMillis),
                        latitude = row.latitude,
                        longitude = row.longitude,
                    ),
                    accuracyMeters = row.accuracyMeters,
                )
            },
            maximumAccuracyMeters = maximumAccuracyMeters,
        ).points
        val semantic = coverageAwareSemanticPoints(
            repository.activeSemanticSegments(journalId, startMillis, endMillis),
            startMillis,
            endMillis,
        )
        val spans = JournalRouteFusion.fuse(
            semanticPoints = semantic,
            detailedPoints = detailed,
            discontinuity = discontinuity,
        )
        val flattened = spans.asSequence()
            .filter { it.source != RouteSource.GAP }
            .flatMap { it.points.asSequence() }
            .distinctBy(::pointKey)
            .sortedBy(GeoPoint::instant)
            .toList()
        return JournalRoute(
            timeline = Timeline(flattened),
            spans = spans,
            detailedInputCount = detailedRows.size,
            detailedUsableCount = detailed.size,
            semanticUsableCount = semantic.size,
        )
    }

    private fun coverageAwareSemanticPoints(
        segments: List<ActiveSemanticSegment>,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<GeoPoint> {
        val coveredByNewerSnapshots = mutableListOf<MillisInterval>()
        val selected = mutableListOf<GeoPoint>()
        segments.groupBy { it.snapshotCapturedAtEpochMillis to it.snapshotId }.forEach { (_, snapshotSegments) ->
            val usable = snapshotSegments.mapNotNull { segment ->
                val decoded = SemanticGeometryCodec.decode(segment.geometryJson)
                    .filter { point ->
                        val instant = point.instant.toEpochMilli()
                        instant >= startEpochMillis &&
                            instant < endExclusiveEpochMillis &&
                            instant >= segment.startEpochMillis &&
                            instant <= segment.endEpochMillis
                    }
                if (decoded.isEmpty()) null else segment to decoded
            }
            usable.forEach { (segment, points) ->
                selected += points.filterNot { point ->
                    coveredByNewerSnapshots.any { point.instant.toEpochMilli() in it }
                }
            }
            coveredByNewerSnapshots += usable.map { (segment, _) ->
                MillisInterval(
                    start = maxOf(startEpochMillis, segment.startEpochMillis),
                    endInclusive = minOf(endExclusiveEpochMillis - 1, segment.endEpochMillis),
                )
            }
        }
        return selected.sortedBy(GeoPoint::instant).distinctBy(::pointKey)
    }

    private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
        point.instant.toEpochMilli(),
        point.latitude.toBits(),
        point.longitude.toBits(),
    )

    private data class MillisInterval(
        val start: Long,
        val endInclusive: Long,
    ) {
        operator fun contains(value: Long): Boolean = value in start..endInclusive
    }
}
