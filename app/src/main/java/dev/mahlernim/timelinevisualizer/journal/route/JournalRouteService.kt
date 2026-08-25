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

enum class JournalRoutePreparationStage {
    PREPARING_DETAILED_ROUTES,
    COMBINING_JOURNEY_HISTORY,
    SAVING_FOR_FASTER_STARTS,
}

/** Reconstructs the active Journal projection and applies detailed-first route fusion. */
class JournalRouteService(
    private val repository: JournalRepository,
    private val projectionStore: JournalRouteProjectionStore = JournalRouteProjectionStore(repository.database),
) {
    suspend fun route(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        maximumAccuracyMeters: Double? = RawSignalProcessor.DEFAULT_MAXIMUM_ACCURACY_METERS,
        discontinuity: Duration = JournalRouteFusion.DEFAULT_DISCONTINUITY,
        onPreparationStage: suspend (JournalRoutePreparationStage) -> Unit = {},
    ): JournalRoute {
        require(endExclusive > start) { "The route range must not be empty" }
        val usesCanonicalSettings = maximumAccuracyMeters == RawSignalProcessor.DEFAULT_MAXIMUM_ACCURACY_METERS &&
            discontinuity == JournalRouteFusion.DEFAULT_DISCONTINUITY
        val isLifetimeRange = start.toEpochMilli() == Long.MIN_VALUE && endExclusive.toEpochMilli() == Long.MAX_VALUE
        if (!usesCanonicalSettings || !isLifetimeRange) {
            return reconstruct(
                journalId,
                start,
                endExclusive,
                maximumAccuracyMeters,
                discontinuity,
                onPreparationStage,
            )
        }

        repository.ensureRouteProjectionState(journalId)
        val stored = projectionStore.read(journalId)
        val state = stored?.state
        val previous = stored?.route
        if (
            state != null && previous != null &&
            state.algorithmVersion == PROJECTION_ALGORITHM_VERSION &&
            state.builtRevision == state.sourceRevision && state.buildStatus == "READY"
        ) {
            return previous
        }

        val rebuilt = if (
            state != null && previous != null &&
            state.algorithmVersion == PROJECTION_ALGORITHM_VERSION &&
            state.dirtyStartEpochMillis != null && state.dirtyEndEpochMillis != null
        ) {
            val dirtyEndExclusive = incrementSafely(state.dirtyEndEpochMillis)
            val (refreshStart, refreshEnd) = previous.expandedRefreshWindow(
                Instant.ofEpochMilli(state.dirtyStartEpochMillis),
                Instant.ofEpochMilli(dirtyEndExclusive),
            )
            val replacement = reconstruct(
                journalId,
                refreshStart,
                refreshEnd,
                maximumAccuracyMeters,
                discontinuity,
                onPreparationStage,
            )
            previous.replacingWindow(refreshStart, refreshEnd, replacement)
        } else {
            reconstruct(
                journalId,
                start,
                endExclusive,
                maximumAccuracyMeters,
                discontinuity,
                onPreparationStage,
            )
        }
        if (state != null) {
            onPreparationStage(JournalRoutePreparationStage.SAVING_FOR_FASTER_STARTS)
            projectionStore.replace(
                journalId = journalId,
                expectedSourceRevision = state.sourceRevision,
                algorithmVersion = PROJECTION_ALGORITHM_VERSION,
                route = rebuilt,
            )
        }
        return rebuilt
    }

    private suspend fun reconstruct(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        maximumAccuracyMeters: Double?,
        discontinuity: Duration,
        onPreparationStage: suspend (JournalRoutePreparationStage) -> Unit,
    ): JournalRoute {
        val startMillis = start.toEpochMilli()
        val endMillis = endExclusive.toEpochMilli()
        onPreparationStage(JournalRoutePreparationStage.PREPARING_DETAILED_ROUTES)
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
        onPreparationStage(JournalRoutePreparationStage.COMBINING_JOURNEY_HISTORY)
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

    private fun incrementSafely(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1

    private fun coverageAwareSemanticPaths(
        segments: List<ActiveSemanticSegment>,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<SemanticRoutePath> {
        val coveredByNewerSnapshots = MergedMillisIntervals()
        val selected = mutableListOf<SemanticRoutePath>()
        segments.groupBy { it.snapshotCapturedAtEpochMillis to it.snapshotId }.forEach { (_, snapshotRows) ->
            val records = snapshotRecords(snapshotRows, startEpochMillis, endExclusiveEpochMillis)
            val preferredIntervals = MergedMillisIntervals(records
                .filter { it.kind in PREFERRED_SEMANTIC_KINDS }
                .map(StoredSemanticRecord::interval))
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
            coveredByNewerSnapshots.addAll(withoutSecondaryHistory.map(StoredSemanticRecord::interval))
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
        excluded: MergedMillisIntervals,
    ): List<StoredSemanticRecord> {
        if (!excluded.overlaps(interval)) return listOf(this)
        val fragments = mutableListOf<MutableList<GeoPoint>>()
        var current: MutableList<GeoPoint>? = null
        var exclusionIndex = 0
        points.forEach { point ->
            val instant = point.instant.toEpochMilli()
            val active = current
            val previousInstant = active?.lastOrNull()?.instant?.toEpochMilli()
            var crossedExcludedInterval = false
            while (
                exclusionIndex < excluded.size &&
                excluded[exclusionIndex].endInclusive < instant
            ) {
                if (
                    previousInstant != null &&
                    excluded[exclusionIndex].start > previousInstant &&
                    excluded[exclusionIndex].start < instant
                ) {
                    crossedExcludedInterval = true
                }
                exclusionIndex += 1
            }
            val exclusion = excluded.getOrNull(exclusionIndex)
            if (exclusion != null && instant in exclusion) {
                current = null
                return@forEach
            }
            if (
                active == null ||
                crossedExcludedInterval ||
                exclusion?.start?.let { start ->
                    start > previousInstant!! && start < instant
                } == true
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

    /** Sorted, non-overlapping intervals used for linear point exclusion sweeps. */
    private class MergedMillisIntervals(intervals: List<MillisInterval> = emptyList()) {
        private var values: List<MillisInterval> = merge(intervals)

        val size: Int get() = values.size

        operator fun get(index: Int): MillisInterval = values[index]

        fun getOrNull(index: Int): MillisInterval? = values.getOrNull(index)

        fun overlaps(interval: MillisInterval): Boolean {
            var low = 0
            var high = values.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (values[middle].endInclusive < interval.start) low = middle + 1 else high = middle
            }
            return low < values.size && values[low].start <= interval.endInclusive
        }

        fun addAll(intervals: List<MillisInterval>) {
            if (intervals.isEmpty()) return
            values = mergeSorted(values, merge(intervals))
        }

        private fun merge(intervals: List<MillisInterval>): List<MillisInterval> {
            if (intervals.isEmpty()) return emptyList()
            val ordered = intervals.sortedWith(compareBy<MillisInterval> { it.start }.thenBy { it.endInclusive })
            val merged = ArrayList<MillisInterval>(ordered.size)
            ordered.forEach { next ->
                val previous = merged.lastOrNull()
                if (previous == null || next.start > previous.endInclusive) {
                    merged += next
                } else if (next.endInclusive > previous.endInclusive) {
                    merged[merged.lastIndex] = MillisInterval(previous.start, next.endInclusive)
                }
            }
            return merged
        }

        private fun mergeSorted(
            first: List<MillisInterval>,
            second: List<MillisInterval>,
        ): List<MillisInterval> {
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first
            val mergedInput = ArrayList<MillisInterval>(first.size + second.size)
            var firstIndex = 0
            var secondIndex = 0
            while (firstIndex < first.size || secondIndex < second.size) {
                if (
                    secondIndex >= second.size ||
                    firstIndex < first.size && first[firstIndex].start <= second[secondIndex].start
                ) {
                    mergedInput += first[firstIndex++]
                } else {
                    mergedInput += second[secondIndex++]
                }
            }
            val result = ArrayList<MillisInterval>(mergedInput.size)
            mergedInput.forEach { next ->
                val previous = result.lastOrNull()
                if (previous == null || next.start > previous.endInclusive) {
                    result += next
                } else if (next.endInclusive > previous.endInclusive) {
                    result[result.lastIndex] = MillisInterval(previous.start, next.endInclusive)
                }
            }
            return result
        }
    }

    private companion object {
        const val PROJECTION_ALGORITHM_VERSION = 1
        const val LEGACY_FLATTENED_PARSER_VERSION = 1
        const val LEGACY_PATH_KIND = "TIMELINE_PATH"
        const val STRUCTURED_PATH_KIND = "PATH"
        val PREFERRED_SEMANTIC_KINDS = setOf("ACTIVITY", "VISIT", "ACTIVITY_AND_VISIT")
    }
}
