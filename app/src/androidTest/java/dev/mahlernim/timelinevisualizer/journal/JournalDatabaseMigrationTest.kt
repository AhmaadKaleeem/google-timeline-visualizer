package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.room.Room
import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JournalDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationPreservesVersionOneJournalDataAndAddsEmptyProjectionTables() = runBlocking<Unit> {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL("INSERT INTO journals VALUES ('journal', 'My Journal', 1, 100, 200, 300, 250, 10, 400, 1, 1)")
            execSQL("INSERT INTO import_batches VALUES ('batch', 'journal', 'hash', 'Timeline.json', 1234, 500, 2, 'LIKELY_SAME', 'COMMITTED', 100, 300, 10, 400, 2, 2, 0, 1, 0)")
            execSQL("INSERT INTO detailed_observations VALUES (1, 'journal', 250, 37.5, 127.0, 'point-key')")
            execSQL("INSERT INTO observation_imports VALUES ('batch', 1, 5.0, 12.0, 3.0, 'gps')")
            execSQL("INSERT INTO semantic_snapshots VALUES ('snapshot', 'batch', 400, 10, 400)")
            execSQL("INSERT INTO semantic_segments VALUES (1, 'snapshot', 0, 10, 400, 'PATH', 'DRIVE', 'place', 'geometry')")
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, JournalDatabase.MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT name, reminderEligible, reminderEnabled, detailedUsableThroughEpochMillis FROM journals WHERE id = 'journal'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("My Journal", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(250L, cursor.getLong(3))
            }
            assertEquals(1, migrated.count("import_batches"))
            assertEquals(1, migrated.count("detailed_observations"))
            assertEquals(1, migrated.count("observation_imports"))
            assertEquals(1, migrated.count("semantic_snapshots"))
            assertEquals(1, migrated.count("semantic_segments"))
            assertEquals(0, migrated.count("route_projection_states"))
            assertEquals(0, migrated.count("route_projection_spans"))
            assertEquals(0, migrated.count("route_projection_chunks"))
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, JournalDatabase::class.java, DATABASE_NAME)
            .addMigrations(JournalDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val state = JournalRepository(database).ensureRouteProjectionState("journal")
            assertEquals(1L, state.sourceRevision)
            assertEquals("DIRTY", state.buildStatus)
            assertNotNull(database.journalDao().journal("journal"))
        } finally {
            database.close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "journal-migration-test.db"
    }
}
