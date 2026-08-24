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
        onProgress: (processedRecordCount: Int, totalRecordCount: Int) -> Unit = { _, _ -> },
    ): JournalImportResult = database.withTransaction {
        dao.insertJournal(journal)
        import(journal.id, input, onProgress)
    }

    suspend fun journal(journalId: String): JournalEntity? = dao.journal(journalId)

    suspend fun primaryJournal(): JournalEntity? = dao.primaryJournal()

    suspend fun committedImport(journalId: String, sourceHash: String): ImportBatchEntity? {
        require(sourceHash.isNotBlank()) { "sourceHash must not be blank" }
        return dao.committedBatchByHash(journalId, sourceHash)
    }

    /**
     * Returns a bounded count of deterministic detail samples that exactly match committed detail.
     *
     * Callers use zero versus nonzero as identity evidence. This deliberately avoids scanning every
     * point in a large rolling export.
     */
    suspend fun detailedOverlapCount(
        journalId: String,
        candidates: List<DetailedObservationInput>,
    ): Int {
        if (candidates.isEmpty()) return 0
        val committedBounds = dao.committedDetailedBounds(journalId)
        val committedStart = committedBounds.startEpochMillis ?: return 0
        val committedEnd = committedBounds.endEpochMillis ?: return 0
        val overlapping = candidates.asSequence()
            .filter { it.instantEpochMillis in committedStart..committedEnd }
            .toList()
        if (overlapping.isEmpty()) return 0
        val samples = deterministicSamples(overlapping, IDENTITY_SAMPLE_SIZE)
            .map(::observationKey)
            .distinct()
        return dao.committedObservationKeyCount(journalId, samples)
    }

    /** True when a bounded, evenly distributed probe provides useful same-Journal evidence. */
    suspend fun hasLikelySameDetailedIdentity(
        journalId: String,
        candidates: List<DetailedObservationInput>,
    ): Boolean {
        if (candidates.isEmpty()) return false
        val sampleCount = minOf(candidates.size, IDENTITY_SAMPLE_SIZE)
        val matches = detailedOverlapCount(journalId, candidates)
        val requiredMatches = minOf(3, (sampleCount + 3) / 4)
        return matches >= requiredMatches
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

    suspend fun import(
        journalId: String,
        input: JournalImport,
        onProgress: (processedRecordCount: Int, totalRecordCount: Int) -> Unit = { _, _ -> },
    ): JournalImportResult =
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

            input.detailedObservations.forEach(::validate)
            var insertedCount = 0
            var processedCount = 0
            val totalRecordCount = input.detailedObservations.size + input.semanticSegments.size
            onProgress(0, totalRecordCount)
            for (chunkStart in input.detailedObservations.indices step OBSERVATION_INSERT_CHUNK_SIZE) {
                val chunkEnd = minOf(chunkStart + OBSERVATION_INSERT_CHUNK_SIZE, input.detailedObservations.size)
                val candidates = input.detailedObservations.subList(chunkStart, chunkEnd)
                val entities = candidates.map { candidate ->
                    DetailedObservationEntity(
                        journalId = journalId,
                        instantEpochMillis = candidate.instantEpochMillis,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude,
                        observationKey = observationKey(candidate),
                    )
                }
                val insertedIds = dao.insertObservations(entities)
                check(insertedIds.size == candidates.size) { "Room returned an unexpected insert result count" }
                val duplicateKeys = insertedIds.mapIndexedNotNull { index, insertedId ->
                    if (insertedId == -1L) entities[index].observationKey else null
                }.distinct()
                val duplicateIds = duplicateKeys
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap { keys -> dao.observationIds(journalId, keys) }
                    .associate { it.observationKey to it.id }
                val provenance = insertedIds.mapIndexed { index, insertedId ->
                    val observationId = if (insertedId == -1L) {
                        requireNotNull(duplicateIds[entities[index].observationKey]) {
                            "An ignored observation could not be resolved"
                        }
                    } else {
                        insertedCount += 1
                        insertedId
                    }
                    val candidate = candidates[index]
                    ObservationImportEntity(
                        importBatchId = batchId,
                        observationId = observationId,
                        accuracyMeters = candidate.accuracyMeters,
                        altitudeMeters = candidate.altitudeMeters,
                        speedMetersPerSecond = candidate.speedMetersPerSecond,
                        provider = candidate.provider,
                    )
                }
                if (provenance.isNotEmpty()) dao.insertObservationImports(provenance)
                processedCount += candidates.size
                onProgress(processedCount, totalRecordCount)
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
                for (chunkStart in input.semanticSegments.indices step SEMANTIC_INSERT_CHUNK_SIZE) {
                    val chunkEnd = minOf(chunkStart + SEMANTIC_INSERT_CHUNK_SIZE, input.semanticSegments.size)
                    val segments = input.semanticSegments.subList(chunkStart, chunkEnd).mapIndexed { offset, segment ->
                        require(segment.endEpochMillis >= segment.startEpochMillis) {
                            "Semantic segment ends before it starts"
                        }
                        SemanticSegmentEntity(
                            snapshotId = snapshotId,
                            sourceOrdinal = chunkStart + offset,
                            startEpochMillis = segment.startEpochMillis,
                            endEpochMillis = segment.endEpochMillis,
                            kind = segment.kind,
                            activityType = segment.activityType,
                            placeId = segment.placeId,
                            geometryJson = segment.geometryJson,
                        )
                    }
                    dao.insertSemanticSegments(segments)
                    processedCount += segments.size
                    onProgress(processedCount, totalRecordCount)
                }
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

    private fun <T> deterministicSamples(items: List<T>, limit: Int): List<T> {
        if (items.size <= limit) return items
        return List(limit) { sampleIndex ->
            val itemIndex = sampleIndex.toLong() * (items.lastIndex).toLong() / (limit - 1)
            items[itemIndex.toInt()]
        }
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
        const val OBSERVATION_INSERT_CHUNK_SIZE = 4_096
        const val SEMANTIC_INSERT_CHUNK_SIZE = 1_000
        const val SQLITE_BIND_CHUNK_SIZE = 900
        const val IDENTITY_SAMPLE_SIZE = 32
    }
}
