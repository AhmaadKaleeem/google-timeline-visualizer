package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        JournalEntity::class,
        ImportBatchEntity::class,
        DetailedObservationEntity::class,
        ObservationImportEntity::class,
        SemanticSnapshotEntity::class,
        SemanticSegmentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao

    companion object {
        fun open(context: Context): JournalDatabase = Room.databaseBuilder(
            context.applicationContext,
            JournalDatabase::class.java,
            "travel-journal.db",
        ).build()
    }
}
