package dev.mahlernim.timelinevisualizer.journal.route

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.journal.DetailedObservationInput
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalImport
import dev.mahlernim.timelinevisualizer.journal.JournalMatchClassification
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.journal.SemanticSegmentInput
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalRouteServiceTest {
    private lateinit var database: JournalDatabase
    private lateinit var repository: JournalRepository
    private lateinit var service: JournalRouteService

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ids = generateSequence(1) { it + 1 }.map { "route-$it" }.iterator()
        repository = JournalRepository(database) { ids.next() }
        service = JournalRouteService(repository)
        repository.createJournal(
            JournalEntity(
                id = JOURNAL_ID,
                name = "My Journal",
                isPrimary = true,
                createdAtEpochMillis = BASE.toEpochMilli(),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun detailedIsCanonicalAndSemanticFillsTheUncoveredInterval() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hybrid",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
                semantic = listOf(semantic(0, 60, listOf(point(0, 9.0), point(30, 9.3), point(60, 9.6)))),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(61)))

        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.SEMANTIC_PATH),
            route.spans.map(RouteSpan::source),
        )
        assertEquals(listOf(1.0, 1.1, 9.3, 9.6), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun detailedDiscontinuityWithoutSemanticCoverageRemainsAGap() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "gapped",
                importedAt = minute(100),
                observations = listOf(
                    observation(0, 1.0),
                    observation(10, 1.1),
                    observation(50, 2.0),
                    observation(60, 2.1),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(61)))

        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.GAP, RouteSource.DETAILED),
            route.spans.map(RouteSpan::source),
        )
        assertEquals("No supported route observations", route.spans[1].transitionReason)
    }

    @Test
    fun newerPartialSemanticSnapshotWinsOnlyInsideItsCoverage() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "old-semantic",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 40, listOf(point(0, 1.0), point(10, 1.1), point(20, 1.2), point(30, 1.3), point(40, 1.4))),
                ),
            ),
        )
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "new-partial",
                importedAt = minute(200),
                semantic = listOf(semantic(20, 30, listOf(point(20, 5.2), point(30, 5.3)))),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(41)))

        assertEquals(listOf(1.0, 1.1, 5.2, 5.3, 1.4), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun appendOnlyImportsReconstructEarlierAndNewDetailWithConcreteOverlap() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "first-window",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
            ),
        )
        val nextWindow = listOf(observation(10, 1.1), observation(20, 1.2))
        assertEquals(1, repository.detailedOverlapCount(JOURNAL_ID, nextWindow))
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "second-window",
                importedAt = minute(200),
                observations = nextWindow,
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(21)))

        assertEquals(listOf(1.0, 1.1, 1.2), route.timeline.points.map(GeoPoint::latitude))
        assertEquals(3, route.detailedInputCount)
        assertNotNull(repository.primaryJournal())
    }

    @Test
    fun adjacentStructuredSemanticRecordsRemainSeparatedWithoutContinuityEvidence() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "independent-segments",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 10, listOf(point(0, 1.0), point(10, 1.1))),
                    semantic(10, 20, listOf(point(10, 2.0), point(20, 2.1))),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(21)))

        assertEquals(
            listOf(RouteSource.SEMANTIC_PATH, RouteSource.GAP, RouteSource.SEMANTIC_PATH),
            route.spans.map(RouteSpan::source),
        )
        assertEquals("No supported route continuity", route.spans[1].transitionReason)
    }

    @Test
    fun legacyParserChunksRemainOneConnectedCompatibilityPath() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "legacy-chunks",
                importedAt = minute(100),
                parserVersion = 1,
                semantic = listOf(
                    semantic(0, 10, listOf(point(0, 1.0), point(10, 1.1))),
                    semantic(20, 30, listOf(point(20, 1.2), point(30, 1.3))),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), route.spans.map(RouteSpan::source))
        assertEquals(listOf(1.0, 1.1, 1.2, 1.3), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun explicitPartsOfOneStructuredRecordRemainConnected() = runBlocking {
        val first = listOf(point(0, 1.0), point(10, 1.1))
        val second = listOf(point(20, 1.2), point(30, 1.3))
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "structured-parts",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 10, first).copy(
                        geometryJson = SemanticGeometryCodec.encodePart(first, "source:7", 0, 2),
                    ),
                    semantic(20, 30, second).copy(
                        geometryJson = SemanticGeometryCodec.encodePart(second, "source:7", 1, 2),
                    ),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), route.spans.map(RouteSpan::source))
    }

    @Test
    fun structuredActivityCoverageSuppressesOverlappingStandalonePathHistory() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "secondary-path",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(10, 20, listOf(point(10, 5.0), point(20, 5.1))),
                    semantic(0, 30, listOf(point(0, 9.0), point(10, 9.1), point(20, 9.2), point(30, 9.3)))
                        .copy(kind = "PATH"),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(9.0, 5.0, 5.1, 9.3), route.timeline.points.map(GeoPoint::latitude))
        assertEquals(2, route.spans.count { it.source == RouteSource.GAP })
    }

    private fun importInput(
        hash: String,
        importedAt: Long,
        observations: List<DetailedObservationInput> = emptyList(),
        semantic: List<SemanticSegmentInput> = emptyList(),
        parserVersion: Int = 2,
    ) = JournalImport(
        sourceHash = hash,
        sourceName = "timeline.json",
        sourceSize = 1_024,
        importedAtEpochMillis = importedAt,
        parserVersion = parserVersion,
        matchClassification = JournalMatchClassification.LIKELY_SAME,
        detailedObservations = observations,
        semanticSegments = semantic,
    )

    private fun observation(minutes: Long, latitude: Double) = DetailedObservationInput(
        instantEpochMillis = minute(minutes),
        latitude = latitude,
        longitude = 127.0 + latitude,
        accuracyMeters = 10.0,
    )

    private fun semantic(
        startMinutes: Long,
        endMinutes: Long,
        points: List<GeoPoint>,
    ) = SemanticSegmentInput(
        startEpochMillis = minute(startMinutes),
        endEpochMillis = minute(endMinutes),
        kind = "ACTIVITY",
        geometryJson = SemanticGeometryCodec.encode(points),
    )

    private fun point(minutes: Long, latitude: Double) = GeoPoint(
        instant = BASE.plus(Duration.ofMinutes(minutes)),
        latitude = latitude,
        longitude = 127.0 + latitude,
    )

    private fun minute(value: Long): Long = BASE.plus(Duration.ofMinutes(value)).toEpochMilli()

    private companion object {
        const val JOURNAL_ID = "journal-route-test"
        val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
