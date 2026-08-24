package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalRepositoryTest {
    private lateinit var database: JournalDatabase
    private lateinit var repository: JournalRepository
    private lateinit var dao: JournalDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.journalDao()
        val ids = generateSequence(1) { it + 1 }.map { "generated-$it" }.iterator()
        repository = JournalRepository(database) { ids.next() }
        runBlocking {
            repository.createJournal(
                JournalEntity(
                    id = JOURNAL_ID,
                    name = "My Journal",
                    isPrimary = true,
                    createdAtEpochMillis = 1_000,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedFileIsNoOpAndRepeatedObservationsKeepProvenance() = runBlocking {
        val first = repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hash-one",
                importedAt = 10_000,
                observations = listOf(observation(2_000), observation(3_000)),
            ),
        ) as JournalImportResult.Committed

        assertEquals(2, first.insertedObservationCount)
        assertEquals(0, first.duplicateObservationCount)
        assertEquals(2, dao.observationCount(JOURNAL_ID))
        assertEquals(2, dao.provenanceCount(first.batchId))

        val duplicateFile = repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hash-one",
                importedAt = 11_000,
                observations = listOf(observation(2_000), observation(3_000)),
            ),
        )
        assertEquals(JournalImportResult.AlreadyImported(first.batchId), duplicateFile)
        assertEquals(2, dao.observationCount(JOURNAL_ID))

        val overlapping = repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hash-two",
                importedAt = 12_000,
                observations = listOf(observation(3_000), observation(4_000)),
            ),
        ) as JournalImportResult.Committed
        assertEquals(1, overlapping.insertedObservationCount)
        assertEquals(1, overlapping.duplicateObservationCount)
        assertEquals(3, dao.observationCount(JOURNAL_ID))
        assertEquals(2, dao.provenanceCount(overlapping.batchId))
        assertEquals(4_000L, dao.journal(JOURNAL_ID)?.detailedCapturedThroughEpochMillis)
        assertEquals(12_000L, dao.journal(JOURNAL_ID)?.lastAdvancedAtEpochMillis)
    }

    @Test
    fun partialSemanticSnapshotDoesNotReplaceOlderUniqueCoverage() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "old",
                importedAt = 10_000,
                segments = listOf(
                    segment(1_000, 2_000, "VISIT"),
                    segment(2_000, 3_000, "ACTIVITY"),
                ),
            ),
        )
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "new-partial",
                importedAt = 20_000,
                segments = listOf(segment(2_000, 3_000, "ACTIVITY")),
            ),
        )

        assertEquals(2, dao.committedSnapshotCount(JOURNAL_ID))
        val segments = dao.committedSemanticSegmentsNewestFirst(JOURNAL_ID)
        assertEquals(3, segments.size)
        assertEquals(2_000L, segments.first().startEpochMillis)
        assertEquals(true, segments.any { it.startEpochMillis == 1_000L })
        val journal = dao.journal(JOURNAL_ID)
        assertEquals(1_000L, journal?.semanticStartEpochMillis)
        assertEquals(3_000L, journal?.semanticEndEpochMillis)
        assertNull(journal?.detailedCapturedThroughEpochMillis)
    }

    @Test
    fun invalidObservationRollsBackTheWholeImport() = runBlocking {
        try {
            repository.import(
                JOURNAL_ID,
                importInput(
                    hash = "invalid",
                    importedAt = 10_000,
                    observations = listOf(observation(2_000), observation(3_000, latitude = 95.0)),
                ),
            )
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals(0, dao.observationCount(JOURNAL_ID))
        assertNull(dao.committedBatchByHash(JOURNAL_ID, "invalid"))
    }

    @Test
    fun likelyDifferentImportCannotMutateTheSelectedJournal() = runBlocking {
        val mismatched = importInput(
            hash = "another-person",
            importedAt = 10_000,
            observations = listOf(observation(2_000)),
        ).copy(matchClassification = JournalMatchClassification.LIKELY_DIFFERENT)

        try {
            repository.import(JOURNAL_ID, mismatched)
        } catch (_: IllegalArgumentException) {
            // Expected. The destination flow must create or explicitly approve another Journal.
        }

        assertEquals(0, dao.observationCount(JOURNAL_ID))
        assertNull(dao.committedBatchByHash(JOURNAL_ID, "another-person"))
    }

    private fun importInput(
        hash: String,
        importedAt: Long,
        observations: List<DetailedObservationInput> = emptyList(),
        segments: List<SemanticSegmentInput> = emptyList(),
    ) = JournalImport(
        sourceHash = hash,
        sourceName = "timeline.json",
        sourceSize = 1_024,
        importedAtEpochMillis = importedAt,
        parserVersion = 1,
        matchClassification = JournalMatchClassification.LIKELY_SAME,
        detailedObservations = observations,
        semanticSegments = segments,
    )

    private fun observation(
        instant: Long,
        latitude: Double = 37.5,
    ) = DetailedObservationInput(
        instantEpochMillis = instant,
        latitude = latitude,
        longitude = 127.0,
        accuracyMeters = 12.0,
    )

    private fun segment(start: Long, end: Long, kind: String) = SemanticSegmentInput(
        startEpochMillis = start,
        endEpochMillis = end,
        kind = kind,
    )

    private companion object {
        const val JOURNAL_ID = "journal-1"
    }
}
