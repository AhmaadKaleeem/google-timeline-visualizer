package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.Journey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TimelineParserTest {
    private val parser = TimelineParser()

    @Test
    fun parsesObjectRootWithTimelinePathAndVisit() {
        val timeline = parse(
            """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-01-03T09:00:00Z",
                  "timelinePath": [
                    {"point": "37.5000°, 127.0000°", "time": "2025-01-03T09:01:00Z"},
                    {"point": "37.6000°, 127.1000°", "time": "2025-01-03T10:01:00Z"}
                  ]
                },
                {
                  "startTime": "2025-02-01T12:00:00+09:00",
                  "visit": {"topCandidate": {"placeLocation": {"latLng": "37.7000°, 127.2000°"}}}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, timeline.points.size)
        assertEquals(listOf(2025), timeline.years)
        assertEquals(37.7, timeline.points.last().latitude, 0.00001)
    }

    @Test
    fun parsesIosArrayWithActivitiesAndStringVisit() {
        val timeline = parse(
            """
            [
              {
                "startTime": "2024-05-01T01:00:00Z",
                "endTime": "2024-05-01T02:00:00Z",
                "activity": {
                  "start": "geo:35.1000,129.1000",
                  "end": {"latLng": "geo:35.2000,129.2000"}
                }
              },
              {
                "startTime": "2024-05-01T03:00:00Z",
                "visit": {"topCandidate": {"placeLocation": "geo:35.3000,129.3000"}}
              }
            ]
            """.trimIndent(),
        )

        assertEquals(3, timeline.points.size)
        assertEquals(35.1, timeline.points.first().latitude, 0.00001)
        assertEquals(35.3, timeline.points.last().latitude, 0.00001)
    }

    @Test
    fun acceptsE7CoordinatesAndRemovesDuplicates() {
        val timeline = parse(
            """
            [{
              "startTime": "2026-01-01T00:00:00Z",
              "visit": {"topCandidate": {"placeLocation": "375000000,1270000000"}},
              "timelinePath": [
                {"point": "375000000,1270000000", "time": "2026-01-01T00:00:00Z"}
              ]
            }]
            """.trimIndent(),
        )

        assertEquals(1, timeline.points.size)
        assertEquals(37.5, timeline.points.single().latitude, 0.00001)
        assertEquals(127.0, timeline.points.single().longitude, 0.00001)
        assertEquals(null, parser.parseCoordinate("geo:91,127"))
    }

    @Test(expected = TimelineParseException::class)
    fun rejectsUnsupportedJson() {
        parse("""{"locations": []}""")
    }

    @Test
    fun journeyUsesDistanceBasedProgress() {
        val timeline = parse(
            """
            [{
              "startTime": "2025-01-01T00:00:00Z",
              "timelinePath": [
                {"point": "37.0,127.0", "time": "2025-01-01T00:00:00Z"},
                {"point": "37.0,127.1", "time": "2025-01-01T01:00:00Z"},
                {"point": "37.0,128.0", "time": "2025-01-01T02:00:00Z"}
              ]
            }]
            """.trimIndent(),
        )
        val journey: Journey = timeline.forYear(2025)

        assertTrue(journey.totalDistanceKm > 80)
        assertEquals(2, journey.pointIndexAt(0.5f))
    }

    private fun parse(json: String) = parser.parse(ByteArrayInputStream(json.toByteArray()))
}
