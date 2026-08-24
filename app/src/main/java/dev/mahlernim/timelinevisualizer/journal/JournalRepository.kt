package dev.mahlernim.timelinevisualizer.journal

import androidx.room.withTransaction
import java.util.UUID

data class DetailedObservationInput(
    val instantEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val provider: String? = null,
)

data class SemanticSegmentInput(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val kind: String,
    val activityType: String? = null,
    val placeId: String? = null,
    val geometryJson: String? = null,
)

enum class JournalMatchClassification {
    LIKELY_SAME,
    UNCERTAIN,
    LIKELY_DIFFERENT,
    NEW_JOURNAL,
    EXPLICITLY_APPROVED,
}

data class JournalImport(
    val sourceHash: String,
    val sourceName: String?,
    val sourceSize: Long?,
    val importedAtEpochMillis: Long,
    val parserVersion: Int,
    val matchClassification: JournalMatchClassification,
    val detailedObservations: List<DetailedObservationInput>,
    val semanticSegments: List<SemanticSegmentInput>,
    val rejectedObservationCount: Int = 0,
    val conflictObservationCount: Int = 0,
)

sealed interface JournalImportResult {
    data class Committed(
        val batchId: String,
        val insertedObservationCount: Int,
        val duplicateObservationCount: Int,
        val semanticSegmentCount: Int,
    ) : JournalImportResult

    data class AlreadyImported(val batchId: String) : JournalImportResult
}

class JournalRepository(
    private val database: JournalDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.journalDao()

    suspend fun createJournal(journal: JournalEntity) = database.withTransaction {
        dao.insertJournal(journal)
    }

    suspend fun createJournalAndImport(
        journal: JournalEntity,
        input: JournalImport,
    ): JournalImportResult = database.withTransaction {
        dao.insertJournal(journal)
        import(journal.id, input)
    }

    suspend fun journal(journalId: String): JournalEntity? = dao.journal(journalId)

    suspend fun primaryJournal(): JournalEntity? = dao.primaryJournal()

    suspend fun committedImport(journalId: String, sourceHash: String): ImportBatchEntity? {
        require(sourceHash.isNotBlank()) { "sourceHash must not be blank" }
        return dao.committedBatchByHash(journalId, sourceHash)
    }

    /** Returns concrete coordinate and timestamp overlap with already committed detail. */
    suspend fun detailedOverlapCount(
        journalId: String,
        candidates: List<DetailedObservationInput>,
    ): Int {
        if (candidates.isEmpty()) return 0
        val keys = candidates.asSequence().map(::observationKey).distinct().toList()
        return keys.chunked(SQLITE_BIND_CHUNK_SIZE).sumOf { chunk ->
            dao.committedObservationKeyCount(journalId, chunk)
        }
    }

    suspend fun activeDetailedObservations(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveDetailedObservation> {
        require(endExclusiveEpochMillis > startEpochMillis) { "The route range must not be empty" }
        return dao.activeDetailedObservations(journalId, startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun activeSemanticSegments(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveSemanticSegment> {
        require(endExclusiveEpochMillis > startEpochMillis) { "The route range must not be empty" }
        return dao.activeSemanticSegmentsNewestFirst(journalId, startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun import(journalId: String, input: JournalImport): JournalImportResult =
        database.withTransaction {
            require(input.sourceHash.isNotBlank()) { "sourceHash must not be blank" }
            val journal = requireNotNull(dao.journal(journalId)) { "Journal does not exist" }
            dao.committedBatchByHash(journalId, input.sourceHash)?.let {
                return@withTransaction JournalImportResult.AlreadyImported(it.id)
            }
            require(
                input.matchClassification in setOf(
                    JournalMatchClassification.LIKELY_SAME,
                    JournalMatchClassification.NEW_JOURNAL,
                    JournalMatchClassification.EXPLICITLY_APPROVED,
                ),
            ) { "This import requires an explicit Journal destination decision" }

            val batchId = idFactory()
            val detailedStart = input.detailedObservations.minOfOrNull { it.instantEpochMillis }
            val detailedEnd = input.detailedObservations.maxOfOrNull { it.instantEpochMillis }
            val semanticStart = input.semanticSegments.minOfOrNull { it.startEpochMillis }
            val semanticEnd = input.semanticSegments.maxOfOrNull { it.endEpochMillis }
            val staging = ImportBatchEntity(
                id = batchId,
                journalId = journalId,
                sourceHash = input.sourceHash,
                sourceName = input.sourceName,
                sourceSize = input.sourceSize,
                importedAtEpochMillis = input.importedAtEpochMillis,
                parserVersion = input.parserVersion,
                matchClassification = input.matchClassification.name,
                status = "STAGING",
                detailedStartEpochMillis = detailedStart,
                detailedEndEpochMillis = detailedEnd,
                semanticStartEpochMillis = semanticStart,
                semanticEndEpochMillis = semanticEnd,
                parsedObservationCount = input.detailedObservations.size,
                rejectedObservationCount = input.rejectedObservationCount,
                conflictObservationCount = input.conflictObservationCount,
            )
            dao.insertBatch(staging)

            var insertedCount = 0
            input.detailedObservations.forEach { candidate ->
                validate(candidate)
                val observationKey = observationKey(candidate)
                val insertedId = dao.insertObservation(
                    DetailedObservationEntity(
                        journalId = journalId,
                        instantEpochMillis = candidate.instantEpochMillis,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude,
                        observationKey = observationKey,
                    ),
                )
                val observationId = if (insertedId == -1L) {
                    dao.observationId(journalId, observationKey)
                } else {
                    insertedCount += 1
                    insertedId
                }
                dao.insertObservationImport(
                    ObservationImportEntity(
                        importBatchId = batchId,
                        observationId = observationId,
                        accuracyMeters = candidate.accuracyMeters,
                        altitudeMeters = candidate.altitudeMeters,
                        speedMetersPerSecond = candidate.speedMetersPerSecond,
                        provider = candidate.provider,
                    ),
                )
            }

            if (input.semanticSegments.isNotEmpty()) {
                val snapshotId = idFactory()
                dao.insertSemanticSnapshot(
                    SemanticSnapshotEntity(
                        id = snapshotId,
                        importBatchId = batchId,
                        capturedAtEpochMillis = input.importedAtEpochMillis,
                        startEpochMillis = semanticStart,
                        endEpochMillis = semanticEnd,
                    ),
                )
                dao.insertSemanticSegments(
                    input.semanticSegments.mapIndexed { index, segment ->
                        require(segment.endEpochMillis >= segment.startEpochMillis) {
                            "Semantic segment ends before it starts"
                        }
                        SemanticSegmentEntity(
                            snapshotId = snapshotId,
                            sourceOrdinal = index,
                            startEpochMillis = segment.startEpochMillis,
                            endEpochMillis = segment.endEpochMillis,
                            kind = segment.kind,
                            activityType = segment.activityType,
                            placeId = segment.placeId,
                            geometryJson = segment.geometryJson,
                        )
                    },
                )
            }

            val duplicateCount = input.detailedObservations.size - insertedCount
            dao.updateBatch(
                staging.copy(
                    status = "COMMITTED",
                    insertedObservationCount = insertedCount,
                    duplicateObservationCount = duplicateCount,
                ),
            )
            val capturedThrough = maxOfNullable(journal.detailedCapturedThroughEpochMillis, detailedEnd)
            val advanced = detailedEnd != null &&
                (journal.detailedCapturedThroughEpochMillis == null || detailedEnd > journal.detailedCapturedThroughEpochMillis)
            dao.updateJournal(
                journal.copy(
                    lastAdvancedAtEpochMillis = if (advanced) input.importedAtEpochMillis else journal.lastAdvancedAtEpochMillis,
                    detailedCapturedThroughEpochMillis = capturedThrough,
                    semanticStartEpochMillis = minOfNullable(journal.semanticStartEpochMillis, semanticStart),
                    semanticEndEpochMillis = maxOfNullable(journal.semanticEndEpochMillis, semanticEnd),
                ),
            )
            JournalImportResult.Committed(
                batchId = batchId,
                insertedObservationCount = insertedCount,
                duplicateObservationCount = duplicateCount,
                semanticSegmentCount = input.semanticSegments.size,
            )
        }

    private fun validate(observation: DetailedObservationInput) {
        require(observation.latitude in -90.0..90.0) { "Latitude is outside the supported range" }
        require(observation.longitude in -180.0..180.0) { "Longitude is outside the supported range" }
        require(observation.latitude.isFinite() && observation.longitude.isFinite()) {
            "Coordinates must be finite"
        }
    }

    private fun observationKey(observation: DetailedObservationInput): String = buildString {
        append(observation.instantEpochMillis)
        append(':')
        append(observation.latitude.toBits().toULong().toString(16))
        append(':')
        append(observation.longitude.toBits().toULong().toString(16))
    }

    private fun minOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

    private fun maxOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private companion object {
        const val SQLITE_BIND_CHUNK_SIZE = 900
    }
}
