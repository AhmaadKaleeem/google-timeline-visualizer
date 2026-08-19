package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode
import dev.mahlernim.timelinevisualizer.ui.LocationFilterPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeTimelineImportDeviceTest {
    @Test
    fun importsDenseLongGapTimelineBelowSixteenMegabytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
        LocationFilterPreferences(context).save(LocationFilterMode.OFF)
        TimelineSourceStore(context).clear()
        val source = File(context.cacheDir, "dense-long-gap-timeline.json")
        writeDenseLongGapTimeline(source, 14L * 1024 * 1024)

        try {
            assertImportCompletes(context, source)
            assertTrue(source.length() >= 14L * 1024 * 1024)
            assertTrue(source.length() < 16L * 1024 * 1024)
        } finally {
            LocationFilterPreferences(context).reset()
            TimelineSourceStore(context).clear()
            source.delete()
        }
    }

    @Test
    fun importsFortyFiveMegabyteTimelineWithoutTerminatingTheApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
        TimelineSourceStore(context).clear()
        val source = File(context.cacheDir, "large-timeline.json")
        writeLargeTimeline(source, 45L * 1024 * 1024)

        try {
            assertImportCompletes(context, source)
            assertTrue(source.length() >= 45L * 1024 * 1024)
        } finally {
            TimelineSourceStore(context).clear()
            source.delete()
        }
    }

    private fun assertImportCompletes(context: Context, source: File) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.fromFile(source)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        var sawLoading = false
        var imported = false
        val sourceStore = TimelineSourceStore(context)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val deadline = System.currentTimeMillis() + 300_000L
            while (System.currentTimeMillis() < deadline && !imported) {
                scenario.onActivity { activity ->
                    val loading = activity.findViewById<View>(R.id.loadingGroup).visibility == View.VISIBLE
                    if (loading) {
                        sawLoading = true
                        assertEquals(false, activity.findViewById<View>(R.id.importButton).isEnabled)
                    }
                    imported = activity.findViewById<View>(R.id.editorGroup).visibility == View.VISIBLE &&
                        !loading && sourceStore.importInProgress() == null
                }
                if (!imported) Thread.sleep(100)
            }
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.editorGroup).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
                assertEquals(true, activity.findViewById<View>(R.id.importButton).isEnabled)
            }
        }
        assertTrue(sawLoading)
        assertTrue(imported)
        assertEquals(null, sourceStore.importInProgress())
    }

    private fun writeDenseLongGapTimeline(file: File, minimumBytes: Long) {
        file.bufferedWriter().use { writer ->
            writer.write("{\"semanticSegments\":[")
            var firstSegment = true
            var pointIndex = 0
            while (true) {
                if (!firstSegment) writer.write(','.code)
                firstSegment = false
                writer.write("{\"startTime\":\"2020-01-01T00:00:00Z\",\"timelinePath\":[")
                repeat(1_000) { offset ->
                    if (offset > 0) writer.write(','.code)
                    val longitude = if (pointIndex % 2 == 0) 0.0 else 179.0
                    writer.write(
                        "{\"point\":\"0.0,$longitude\"," +
                            "\"durationMinutesOffsetFromStartTime\":$pointIndex}",
                    )
                    pointIndex += 1
                }
                writer.write("]}")
                writer.flush()
                if (file.length() >= minimumBytes) break
            }
            writer.write("]}")
        }
    }

    private fun writeLargeTimeline(file: File, minimumBytes: Long) {
        file.bufferedWriter().use { writer ->
            writer.write("{\"semanticSegments\":[")
            var firstSegment = true
            var pointIndex = 0
            var segmentCount = 0
            while (true) {
                if (!firstSegment) writer.write(','.code)
                firstSegment = false
                writer.write("{\"startTime\":\"2020-01-01T00:00:00Z\",\"timelinePath\":[")
                repeat(10) { offset ->
                    if (offset > 0) writer.write(','.code)
                    val latitude = 35.0 + (pointIndex % 100_000) / 1_000_000.0
                    val longitude = 126.0 + (pointIndex % 100_000) / 1_000_000.0
                    writer.write(
                        "{\"point\":\"$latitude,$longitude\"," +
                            "\"durationMinutesOffsetFromStartTime\":$pointIndex}",
                    )
                    pointIndex += 1
                }
                writer.write("],\"testPadding\":\"")
                repeat(3_072) { writer.write('x'.code) }
                writer.write("\"}")
                segmentCount += 1
                if (segmentCount % 100 == 0) {
                    writer.flush()
                    if (file.length() >= minimumBytes) break
                }
            }
            writer.write("]}")
        }
    }
}
