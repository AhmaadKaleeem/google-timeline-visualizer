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
}
