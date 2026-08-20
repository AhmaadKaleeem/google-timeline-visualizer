package dev.mahlernim.timelinevisualizer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelinePainterTest {
    @Test
    fun firstPreviewFrameRendersInEverySupportedLocale() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val journey = Journey.from(
            listOf(
                GeoPoint(Instant.parse("2025-01-01T00:00:00Z"), 37.50, 126.90),
                GeoPoint(Instant.parse("2025-02-01T00:00:00Z"), 37.55, 126.95),
            ),
            2025,
        )

        listOf("en", "de", "es", "fr", "ja", "ko", "pt-BR", "zh-CN", "zh-TW").forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val configuration = Configuration(application.resources.configuration).apply { setLocale(locale) }
            val localized = application.createConfigurationContext(configuration)
            val renderText = RenderText(
                localeTag = tag,
                fallbackTitle = localized.getString(R.string.default_title),
                datePattern = localized.getString(R.string.render_date_pattern),
                distanceUnit = localized.getString(R.string.distance_unit),
                attribution = localized.getString(R.string.map_attribution),
            )
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            try {
                TimelinePainter().draw(
                    canvas = Canvas(bitmap),
                    width = SIZE,
                    height = SIZE,
                    journey = journey,
                    frame = TimelineFrame(0f, 0f),
                    journeyDurationSeconds = 45,
                    title = renderText.fallbackTitle,
                    renderText = renderText,
                    allowCameraTrackBuild = false,
                    tiles = { null },
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun fixedCameraKeepsTheSameZoomSpanAcrossTheJourney() {
        val journey = Journey.from(
            listOf(
                point(37.50, 126.90),
                point(37.55, 126.95),
                point(37.60, 127.00),
            ),
            2025,
        )
        val fixed = CameraSettings(
            cameraMovement = CameraMovement.FIXED,
            longTripCompression = LongTripCompression.OFF,
        )
        val painter = TimelinePainter()
        val spans = listOf(0f, 0.2f, 0.5f, 0.8f, 1f).map { progress ->
            val viewport = painter.viewport(journey, progress, SIZE, SIZE, fixed)
            viewport.maxY - viewport.minY
        }

        spans.forEach { assertEquals(spans.first(), it, 1e-12) }
    }

    @Test
    fun routeStartsEmptyAndAppearsOnlyAsJourneyAdvances() {
        val points = listOf(
            GeoPoint(Instant.parse("2025-01-01T00:00:00Z"), 37.45, 126.75),
            GeoPoint(Instant.parse("2025-04-01T00:00:00Z"), 37.65, 127.20),
            GeoPoint(Instant.parse("2025-08-01T00:00:00Z"), 37.35, 127.55),
            GeoPoint(Instant.parse("2025-12-01T00:00:00Z"), 37.75, 127.90),
        )
        val journey = Journey.from(points, 2025)
        val start = render(journey, 0f)
        val halfway = render(journey, 0.5f)

        val startRoutePixels = countRouteColoredPixels(start)
        val halfwayRoutePixels = countRouteColoredPixels(halfway)

        assertTrue("The initial marker used $startRoutePixels route-colored pixels", startRoutePixels < 500)
        assertTrue(
            "The traveled route should be visibly longer ($startRoutePixels vs $halfwayRoutePixels pixels)",
            halfwayRoutePixels > startRoutePixels * 2,
        )
    }

    @Test
    fun endingOverviewFitsAWorldwideJourney() {
        val points = listOf(
            GeoPoint(Instant.parse("2025-01-01T00:00:00Z"), 10.0, -150.0),
            GeoPoint(Instant.parse("2025-06-01T00:00:00Z"), 10.0, 0.0),
            GeoPoint(Instant.parse("2025-12-01T00:00:00Z"), 10.0, 150.0),
        )
        val journey = Journey.from(points, 2025)
        val viewport = TimelinePainter().viewport(journey, TimelineFrame(1f, 1f), SIZE, SIZE)

        assertTrue("The ending should fit the complete worldwide route", viewport.maxX - viewport.minX > 0.8)
    }

    @Test
    fun endingOverviewKeepsEveryRouteInsideTheAreaBelowTheHeader() {
        val routes = listOf(
            listOf(point(70.0, 127.0), point(-55.0, 127.0)),
            listOf(point(35.0, -120.0), point(35.0, 140.0)),
            listOf(point(37.50, 126.95), point(37.55, 127.05)),
            listOf(point(10.0, -150.0), point(10.0, 0.0), point(10.0, 150.0)),
            listOf(point(10.0, 179.0), point(10.0, -179.0)),
        )
        for (size in listOf(480, 1080)) {
            routes.forEach { points ->
                val journey = Journey.from(points, 2025)
                val painter = TimelinePainter()
                val viewport = painter.viewport(journey, TimelineFrame(1f, 1f), size, size)
                val safe = painter.overviewSafeArea(size, size)
                val centerX = (viewport.minX + viewport.maxX) / 2.0
                journey.renderPath.forEach { sample ->
                    val projected = dev.mahlernim.timelinevisualizer.model.WebMercator.project(sample.point)
                    val x = unwrapNear(projected.x, centerX)
                    val screenX = ((x - viewport.minX) / (viewport.maxX - viewport.minX) * size).toFloat()
                    val screenY = ((projected.y - viewport.minY) / (viewport.maxY - viewport.minY) * size).toFloat()
                    assertTrue("Route x $screenX was outside $safe at $size", screenX in safe.left..safe.right)
                    assertTrue("Route y $screenY was outside $safe at $size", screenY in safe.top..safe.bottom)
                }
            }
        }
    }

    @Test
    fun titleCardFitsAndStaysProportionateInEveryExportShape() {
        val painter = TimelinePainter()
        listOf(480 to 480, 1080 to 1080, 1080 to 1920, 1920 to 1080).forEach { (width, height) ->
            val card = painter.overlayCard(width, height)

            assertTrue("Card escaped ${width}x$height", card.left >= 0f && card.right <= width)
            assertTrue("Card escaped ${width}x$height", card.top >= 0f && card.bottom <= height)
            assertEquals(width / 2f, card.centerX(), 0.5f)
        }

        val square = painter.overlayCard(1080, 1080)
        val landscape = painter.overlayCard(1920, 1080)
        assertEquals(square.width(), landscape.width(), 0.5f)
        assertTrue(landscape.width() < 1920 * 0.75f)
    }

    @Test
    fun indexedCameraBoundsMatchThePreviousRouteScan() {
        val routes = listOf(
            List(2_000) { index ->
                GeoPoint(
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(index.toLong()),
                    37.45 + (index % 100) * 0.00001,
                    126.90 + index * 0.00001,
                )
            },
            listOf(point(10.0, 179.0), point(20.0, -179.0), point(-15.0, 170.0)),
            listOf(point(70.0, -150.0), point(-55.0, 0.0), point(60.0, 150.0)),
        )
        val settings = listOf(
            CameraSettings.DEFAULT,
            CameraSettings(cameraMovement = CameraMovement.FIXED, longTripCompression = LongTripCompression.OFF),
            CameraSettings(cameraMovement = CameraMovement.DYNAMIC, longTripCompression = LongTripCompression.STRONG),
        )

        routes.forEach { points ->
            val journey = Journey.from(points, 2025)
            settings.forEach { cameraSettings ->
                listOf(0f, 0.17f, 0.5f, 0.83f, 1f).forEach { progress ->
                    val indexed = TimelinePainter().rawViewportForTest(
                        journey, progress, SIZE, SIZE, cameraSettings, useRangeIndex = true,
                    )
                    val previous = TimelinePainter().rawViewportForTest(
                        journey, progress, SIZE, SIZE, cameraSettings, useRangeIndex = false,
                    )
                    assertEquals(previous, indexed)
                }
            }
        }
    }

    @Test
    fun denseCameraTrackDoesNotRescanEveryPointForEveryCameraSample() {
        val points = List(20_000) { index ->
            GeoPoint(
                Instant.parse("2025-01-01T00:00:00Z").plusSeconds(index.toLong()),
                37.50 + (index % 100) * 0.000001,
                126.95 + index * 0.000001,
            )
        }
        val journey = Journey.from(points, 2025)
        val painter = TimelinePainter()

        painter.viewport(journey, 0f, SIZE, SIZE)

        assertTrue(
            "Camera evaluated ${painter.cameraRoutePointEvaluations} route points",
            painter.cameraRoutePointEvaluations < 100_000,
        )
    }

    @Test
    fun lightweightInitialFrameDoesNotPrepareTheFullRoute() {
        val points = List(5_000) { index ->
            GeoPoint(
                Instant.parse("2025-01-01T00:00:00Z").plusSeconds(index.toLong()),
                37.50 + (index % 100) * 0.000001,
                126.95 + index * 0.000001,
            )
        }
        val journey = Journey.from(points, 2025)
        val painter = TimelinePainter()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

        painter.draw(
            canvas = Canvas(bitmap),
            width = SIZE,
            height = SIZE,
            journey = journey,
            frame = TimelineFrame(0f, 0f),
            journeyDurationSeconds = 45,
            title = "Timeline",
            allowCameraTrackBuild = false,
            tiles = { null },
        )

        assertEquals(0L, painter.cameraRoutePointEvaluations)
        bitmap.recycle()
    }

    private fun point(latitude: Double, longitude: Double) = GeoPoint(
        Instant.parse("2025-06-01T00:00:00Z"),
        latitude,
        longitude,
    )

    private fun unwrapNear(value: Double, reference: Double): Double {
        var result = value
        while (result - reference > 0.5) result -= 1.0
        while (result - reference < -0.5) result += 1.0
        return result
    }

    private fun render(journey: Journey, progress: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        TimelinePainter().draw(Canvas(bitmap), SIZE, SIZE, journey, progress, "Timeline") { null }
        return bitmap
    }

    private fun countRouteColoredPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) > 180 && Color.green(color) < 190 && Color.blue(color) < 210) count++
            }
        }
        return count
    }

    companion object {
        private const val SIZE = 360
    }
}
