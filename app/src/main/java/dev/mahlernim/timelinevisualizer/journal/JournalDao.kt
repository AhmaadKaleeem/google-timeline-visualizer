package dev.mahlernim.timelinevisualizer.journal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface JournalDao {
    @Insert
    suspend fun insertJournal(journal: JournalEntity)

    @Query("SELECT * FROM journals WHERE id = :journalId")
    suspend fun journal(journalId: String): JournalEntity?

    @Query("SELECT * FROM journals WHERE isPrimary = 1 ORDER BY createdAtEpochMillis ASC LIMIT 1")
    suspend fun primaryJournal(): JournalEntity?

    @Update
    suspend fun updateJournal(journal: JournalEntity)

    @Insert
    suspend fun insertBatch(batch: ImportBatchEntity)

    @Update
    suspend fun updateBatch(batch: ImportBatchEntity)

    @Query("SELECT * FROM import_batches WHERE journalId = :journalId AND sourceHash = :sourceHash AND status = 'COMMITTED' LIMIT 1")
    suspend fun committedBatchByHash(journalId: String, sourceHash: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE id = :batchId")
    suspend fun batch(batchId: String): ImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservation(observation: DetailedObservationEntity): Long

    @Query("SELECT id FROM detailed_observations WHERE journalId = :journalId AND observationKey = :observationKey")
    suspend fun observationId(journalId: String, observationKey: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservationImport(provenance: ObservationImportEntity)

    @Insert
    suspend fun insertSemanticSnapshot(snapshot: SemanticSnapshotEntity)

    @Insert
    suspend fun insertSemanticSegments(segments: List<SemanticSegmentEntity>)

    @Query("SELECT COUNT(*) FROM detailed_observations WHERE journalId = :journalId")
    suspend fun observationCount(journalId: String): Int

    @Query("SELECT COUNT(*) FROM observation_imports WHERE importBatchId = :batchId")
    suspend fun provenanceCount(batchId: String): Int

    @Query("SELECT COUNT(*) FROM semantic_snapshots INNER JOIN import_batches ON import_batches.id = semantic_snapshots.importBatchId WHERE import_batches.journalId = :journalId AND import_batches.status = 'COMMITTED'")
    suspend fun committedSnapshotCount(journalId: String): Int

    @Query("SELECT semantic_segments.* FROM semantic_segments INNER JOIN semantic_snapshots ON semantic_snapshots.id = semantic_segments.snapshotId INNER JOIN import_batches ON import_batches.id = semantic_snapshots.importBatchId WHERE import_batches.journalId = :journalId AND import_batches.status = 'COMMITTED' ORDER BY semantic_snapshots.capturedAtEpochMillis DESC, semantic_segments.sourceOrdinal ASC")
    suspend fun committedSemanticSegmentsNewestFirst(journalId: String): List<SemanticSegmentEntity>

    @Query(
        """
        SELECT detailed_observations.instantEpochMillis,
               detailed_observations.latitude,
               detailed_observations.longitude,
               MIN(observation_imports.accuracyMeters) AS accuracyMeters
        FROM detailed_observations
        INNER JOIN observation_imports
            ON observation_imports.observationId = detailed_observations.id
        INNER JOIN import_batches
            ON import_batches.id = observation_imports.importBatchId
        WHERE detailed_observations.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND detailed_observations.instantEpochMillis >= :startEpochMillis
          AND detailed_observations.instantEpochMillis < :endExclusiveEpochMillis
          AND observation_imports.accuracyMeters IS NOT NULL
        GROUP BY detailed_observations.id
        ORDER BY detailed_observations.instantEpochMillis ASC,
                 detailed_observations.id ASC
        """,
    )
    suspend fun activeDetailedObservations(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveDetailedObservation>

    @Query(
        """
        SELECT semantic_segments.*,
               import_batches.parserVersion AS parserVersion,
               semantic_snapshots.capturedAtEpochMillis AS snapshotCapturedAtEpochMillis
        FROM semantic_segments
        INNER JOIN semantic_snapshots
            ON semantic_snapshots.id = semantic_segments.snapshotId
        INNER JOIN import_batches
            ON import_batches.id = semantic_snapshots.importBatchId
        WHERE import_batches.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND semantic_segments.endEpochMillis >= :startEpochMillis
          AND semantic_segments.startEpochMillis < :endExclusiveEpochMillis
        ORDER BY semantic_snapshots.capturedAtEpochMillis DESC,
                 semantic_snapshots.id DESC,
                 semantic_segments.sourceOrdinal ASC
        """,
    )
    suspend fun activeSemanticSegmentsNewestFirst(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveSemanticSegment>

    @Query(
        """
        SELECT COUNT(DISTINCT detailed_observations.observationKey)
        FROM detailed_observations
        INNER JOIN observation_imports
            ON observation_imports.observationId = detailed_observations.id
        INNER JOIN import_batches
            ON import_batches.id = observation_imports.importBatchId
        WHERE detailed_observations.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND detailed_observations.observationKey IN (:observationKeys)
        """,
    )
    suspend fun committedObservationKeyCount(
        journalId: String,
        observationKeys: List<String>,
    ): Int
}
