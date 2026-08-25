package dev.mahlernim.timelinevisualizer.journal.route

import androidx.room.withTransaction
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.RouteProjectionChunkEntity
import dev.mahlernim.timelinevisualizer.journal.RouteProjectionSpanEntity
import dev.mahlernim.timelinevisualizer.journal.RouteProjectionStateEntity
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant
import kotlinx.coroutines.CancellationException

data class StoredJournalRouteProjection(
    val state: RouteProjectionStateEntity,
    val route: JournalRoute?,
)

/** Reads and atomically replaces the derived route projection. Source Journal rows are never changed. */
class JournalRouteProjectionStore(
    private val database: JournalDatabase,
) {
    private val dao = database.journalDao()

    suspend fun state(journalId: String): RouteProjectionStateEntity? = dao.routeProjectionState(journalId)

    suspend fun read(journalId: String): StoredJournalRouteProjection? {
        val state = dao.routeProjectionState(journalId) ?: return null
        if (state.builtRevision <= 0L) return StoredJournalRouteProjection(state, null)
        return try {
            val spanRows = dao.routeProjectionSpans(journalId)
            val chunks = if (spanRows.isEmpty()) emptyMap() else {
                spanRows.map(RouteProjectionSpanEntity::id)
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap { ids -> dao.routeProjectionChunks(ids) }
                    .groupBy(RouteProjectionChunkEntity::spanId)
            }
            val spans = spanRows.map { span ->
                val spanChunks = chunks[span.id].orEmpty()
                require(spanChunks.map(RouteProjectionChunkEntity::chunkOrdinal) == spanChunks.indices.toList())
                val points = spanChunks.flatMap { chunk ->
                    RouteProjectionPointCodec.decode(chunk.pointData, chunk.pointCount, chunk.formatVersion)
                }
                require(points.size == span.pointCount)
                RouteSpan(
                    start = Instant.ofEpochMilli(span.startEpochMillis),
                    end = Instant.ofEpochMilli(span.endEpochMillis),
                    source = RouteSource.valueOf(span.source),
                    points = points,
                    transitionReason = span.transitionReason,
                )
            }
            require(spans.size == state.spanCount)
            val timelinePoints = spans.asSequence()
                .filter { it.source != RouteSource.GAP }
                .flatMap { it.points.asSequence() }
                .distinctBy(::pointKey)
                .sortedBy(GeoPoint::instant)
                .toList()
            require(timelinePoints.size == state.pointCount)
            StoredJournalRouteProjection(
                state,
                JournalRoute(
                    timeline = Timeline(timelinePoints),
                    spans = spans,
                    detailedInputCount = state.detailedInputCount,
                    detailedUsableCount = state.detailedUsableCount,
                    semanticUsableCount = state.semanticUsableCount,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            StoredJournalRouteProjection(state, null)
        }
    }

    /** Returns false if an import advanced the source while this projection was being prepared. */
    suspend fun replace(
        journalId: String,
        expectedSourceRevision: Long,
        algorithmVersion: Int,
        route: JournalRoute,
        projectionStartEpochMillis: Long = Long.MIN_VALUE,
        projectionEndExclusiveEpochMillis: Long = Long.MAX_VALUE,
        updatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = database.withTransaction {
        require(projectionEndExclusiveEpochMillis > projectionStartEpochMillis)
        val current = dao.routeProjectionState(journalId) ?: return@withTransaction false
        if (current.sourceRevision != expectedSourceRevision) return@withTransaction false

        dao.deleteRouteProjectionSpans(journalId)
        route.spans.forEachIndexed { ordinal, span ->
            val spanId = dao.insertRouteProjectionSpan(
                RouteProjectionSpanEntity(
                    journalId = journalId,
                    ordinal = ordinal,
                    startEpochMillis = span.start.toEpochMilli(),
                    endEpochMillis = span.end.toEpochMilli(),
                    source = span.source.name,
                    transitionReason = span.transitionReason,
                    pointCount = span.points.size,
                ),
            )
            val chunks = span.points.chunked(RouteProjectionPointCodec.MAX_POINTS_PER_CHUNK)
                .mapIndexed { chunkOrdinal, points ->
                    RouteProjectionChunkEntity(
                        spanId = spanId,
                        chunkOrdinal = chunkOrdinal,
                        formatVersion = RouteProjectionPointCodec.FORMAT_VERSION,
                        pointCount = points.size,
                        pointData = RouteProjectionPointCodec.encode(points),
                    )
                }
            if (chunks.isNotEmpty()) dao.insertRouteProjectionChunks(chunks)
        }
        dao.updateRouteProjectionState(
            current.copy(
                builtRevision = expectedSourceRevision,
                algorithmVersion = algorithmVersion,
                buildStatus = "READY",
                dirtyStartEpochMillis = null,
                dirtyEndEpochMillis = null,
                projectionStartEpochMillis = projectionStartEpochMillis,
                projectionEndExclusiveEpochMillis = projectionEndExclusiveEpochMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
                spanCount = route.spans.size,
                pointCount = route.timeline.points.size,
                detailedInputCount = route.detailedInputCount,
                detailedUsableCount = route.detailedUsableCount,
                semanticUsableCount = route.semanticUsableCount,
            ),
        )
        true
    }

    private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
        point.instant.toEpochMilli(),
        point.latitude.toBits(),
        point.longitude.toBits(),
    )

    private companion object {
        const val SQLITE_BIND_CHUNK_SIZE = 900
    }
}
