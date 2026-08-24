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
        val semanticPaths = coverageAwareSemanticPaths(
            repository.activeSemanticSegments(journalId, startMillis, endMillis),
            startMillis,
            endMillis,
        )
        val spans = JournalRouteFusion.fuseSemanticPaths(
            semanticPaths = semanticPaths,
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
            semanticUsableCount = semanticPaths.sumOf { it.points.size },
        )
    }

    private fun coverageAwareSemanticPaths(
        segments: List<ActiveSemanticSegment>,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<SemanticRoutePath> {
        val coveredByNewerSnapshots = mutableListOf<MillisInterval>()
        val selected = mutableListOf<SemanticRoutePath>()
        segments.groupBy { it.snapshotCapturedAtEpochMillis to it.snapshotId }.forEach { (_, snapshotRows) ->
            val records = snapshotRecords(snapshotRows, startEpochMillis, endExclusiveEpochMillis)
            val preferredIntervals = records
                .filter { it.kind in PREFERRED_SEMANTIC_KINDS }
                .map(StoredSemanticRecord::interval)
            val withoutSecondaryHistory = records.flatMap { record ->
                if (record.kind == STRUCTURED_PATH_KIND) {
                    record.fragmentsOutside(preferredIntervals)
                } else {
                    listOf(record)
                }
            }
            withoutSecondaryHistory.forEach { record ->
                record.fragmentsOutside(coveredByNewerSnapshots).forEachIndexed { index, fragment ->
                    selected += fragment.toRoutePath("${fragment.id}:selected:$index")
                }
            }
            coveredByNewerSnapshots += withoutSecondaryHistory.map(StoredSemanticRecord::interval)
        }
        return selected.sortedWith(compareBy<SemanticRoutePath> { it.start }.thenBy(SemanticRoutePath::id))
    }

    private fun snapshotRecords(
        rows: List<ActiveSemanticSegment>,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<StoredSemanticRecord> {
        if (rows.isEmpty()) return emptyList()
        val ordered = rows.sortedBy(ActiveSemanticSegment::sourceOrdinal)
        if (ordered.first().parserVersion <= LEGACY_FLATTENED_PARSER_VERSION) {
            val points = ordered.flatMap { SemanticGeometryCodec.decode(it.geometryJson) }
                .filterToRange(startEpochMillis, endExclusiveEpochMillis)
            if (points.isEmpty()) return emptyList()
            return listOf(
                StoredSemanticRecord(
                    id = "${ordered.first().snapshotId}:legacy",
                    kind = LEGACY_PATH_KIND,
                    startEpochMillis = maxOf(startEpochMillis, ordered.minOf(ActiveSemanticSegment::startEpochMillis)),
                    endEpochMillis = minOf(
                        endExclusiveEpochMillis - 1,
                        ordered.maxOf(ActiveSemanticSegment::endEpochMillis),
                    ),
                    points = points,
                ),
            )
        }

        val decoded = ordered.mapNotNull { row ->
            val geometry = SemanticGeometryCodec.decodeGeometry(row.geometryJson)
            val points = geometry.points.filterToRange(startEpochMillis, endExclusiveEpochMillis)
            if (points.isEmpty()) null else DecodedRow(row, geometry, points)
        }
        val grouped = decoded.groupBy { decodedRow ->
            decodedRow.geometry.continuityGroup?.let { "group:$it" } ?: "row:${decodedRow.row.id}"
        }
        return grouped.values.flatMap { parts ->
            coalescedRecord(parts, startEpochMillis, endExclusiveEpochMillis)?.let(::listOf)
                ?: parts.map { part -> part.toRecord(startEpochMillis, endExclusiveEpochMillis) }
        }
    }

    private fun coalescedRecord(
        parts: List<DecodedRow>,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): StoredSemanticRecord? {
        val ordered = parts.sortedBy { it.geometry.partIndex }
        val expectedCount = ordered.firstOrNull()?.geometry?.partCount ?: return null
        if (
            expectedCount != ordered.size ||
            ordered.map { it.geometry.partIndex } != (0 until expectedCount).toList() ||
            ordered.any { it.geometry.partCount != expectedCount }
        ) {
            return null
        }
        return StoredSemanticRecord(
            id = "${ordered.first().row.snapshotId}:${ordered.first().geometry.continuityGroup}",
            kind = ordered.first().row.kind,
            startEpochMillis = maxOf(startEpochMillis, ordered.minOf { it.row.startEpochMillis }),
            endEpochMillis = minOf(endExclusiveEpochMillis - 1, ordered.maxOf { it.row.endEpochMillis }),
            points = normalize(ordered.flatMap(DecodedRow::points)),
        )
    }

    private fun DecodedRow.toRecord(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ) = StoredSemanticRecord(
        id = "${row.snapshotId}:row:${row.id}",
        kind = row.kind,
        startEpochMillis = maxOf(startEpochMillis, row.startEpochMillis),
        endEpochMillis = minOf(endExclusiveEpochMillis - 1, row.endEpochMillis),
        points = points,
    )

    private fun StoredSemanticRecord.fragmentsOutside(
        excluded: List<MillisInterval>,
    ): List<StoredSemanticRecord> {
        if (excluded.none { it.overlaps(interval) }) return listOf(this)
        val fragments = mutableListOf<MutableList<GeoPoint>>()
        var current: MutableList<GeoPoint>? = null
        points.forEach { point ->
            val instant = point.instant.toEpochMilli()
            if (excluded.any { instant in it }) {
                current = null
                return@forEach
            }
            val active = current
            if (
                active == null ||
                excluded.any { interval ->
                    val previous = active.last().instant.toEpochMilli()
                    interval.start > previous && interval.start < instant
                }
            ) {
                current = mutableListOf<GeoPoint>().also(fragments::add)
            }
            current?.add(point)
        }
        return fragments.filter { it.isNotEmpty() }.mapIndexed { index, fragment ->
            copy(
                id = "$id:fragment:$index",
                startEpochMillis = fragment.first().instant.toEpochMilli(),
                endEpochMillis = fragment.last().instant.toEpochMilli(),
                points = fragment,
            )
        }
    }

    private fun StoredSemanticRecord.toRoutePath(routeId: String) = SemanticRoutePath(
        id = routeId,
        start = Instant.ofEpochMilli(startEpochMillis),
        end = Instant.ofEpochMilli(endEpochMillis),
        points = points,
    )

    private fun List<GeoPoint>.filterToRange(start: Long, endExclusive: Long): List<GeoPoint> =
        normalize(filter { it.instant.toEpochMilli() in start until endExclusive })

    private fun normalize(points: List<GeoPoint>): List<GeoPoint> = points
        .sortedBy(GeoPoint::instant)
        .distinctBy(::pointKey)

    private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
        point.instant.toEpochMilli(),
        point.latitude.toBits(),
        point.longitude.toBits(),
    )

    private data class DecodedRow(
        val row: ActiveSemanticSegment,
        val geometry: SemanticGeometryCodec.Geometry,
        val points: List<GeoPoint>,
    )

    private data class StoredSemanticRecord(
        val id: String,
        val kind: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val points: List<GeoPoint>,
    ) {
        val interval = MillisInterval(startEpochMillis, endEpochMillis)
    }

    private data class MillisInterval(
        val start: Long,
        val endInclusive: Long,
    ) {
        operator fun contains(value: Long): Boolean = value in start..endInclusive

        fun overlaps(other: MillisInterval): Boolean = start <= other.endInclusive && other.start <= endInclusive
    }

    private companion object {
        const val LEGACY_FLATTENED_PARSER_VERSION = 1
        const val LEGACY_PATH_KIND = "TIMELINE_PATH"
        const val STRUCTURED_PATH_KIND = "PATH"
        val PREFERRED_SEMANTIC_KINDS = setOf("ACTIVITY", "VISIT", "ACTIVITY_AND_VISIT")
    }
}
