package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.videos.VideoDataSource
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalLabUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun resetJournal() {
        TimelineSourceStore(context).clear()
        context.deleteDatabase("travel-journal.db")
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun creationUsesOneAutomaticJournalSourceWithoutRawChoice() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.rawDataChoice).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automaticJournalSourceText).visibility)
        val sourceMessage = activity.getString(R.string.journal_automatic_source_summary)
        assertTrue(sourceMessage.contains("automatically use your most detailed saved routes"))
        assertTrue(sourceMessage.contains("best available Timeline history"))
        assertFalse(sourceMessage.contains("raw data", ignoreCase = true))
    }

    @Test
    fun settingsPresentTimelineImportAsGrowingTheTravelJournal() {
        assertEquals("Travel Journal", context.getString(R.string.timeline_data))
        assertEquals("Grow Travel Journal", context.getString(R.string.import_or_update))
        assertTrue(context.getString(R.string.timeline_not_imported).contains("ready to grow"))
        assertEquals("Title shown in video", context.getString(R.string.video_title_template))
        assertEquals(
            "Edit the title that appears in this video.",
            context.getString(R.string.title_template_help),
        )
        assertFalse(context.getString(R.string.title_template_help).contains("{year}"))
    }

    @Test
    fun firstImportShowsThatTheTravelJournalIsBeingCreated() {
        val source = rawTimeline("progress", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationSettings).performClick()

        activity.importTimeline(Uri.fromFile(source))

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsTimelineProgressGroup).visibility)
        val firstImportText = activity.findViewById<TextView>(R.id.settingsImportTimelineButton).text.toString()
        assertTrue(
            firstImportText == activity.getString(R.string.journal_import_preparing) ||
                firstImportText == activity.getString(R.string.journal_import_creating),
        )
        assertFalse(firstImportText == activity.getString(R.string.journal_import_in_progress))
        waitUntil {
            activity.findViewById<View>(R.id.settingsTimelineProgressGroup).visibility == View.GONE
        }
        assertEquals(
            activity.getString(R.string.import_or_update),
            activity.findViewById<TextView>(R.id.settingsImportTimelineButton).text.toString(),
        )
        val result = ShadowDialog.getLatestDialog()
        assertEquals(
            activity.getString(R.string.journal_created_title),
            result.findViewById<TextView>(R.id.journalGrowthHeadline)?.text,
        )
    }

    @Test
    fun recentDetailedImportOffersOptionalRemindersAndShowsStructuredStatus() {
        val source = rawTimeline("recent", 37.5, 127.0, java.time.Instant.now().minusSeconds(60).toString())
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)
        ShadowDialog.getLatestDialog()?.dismiss()
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        waitUntil { activity.findViewById<View>(R.id.journalReminderSwitch).visibility == View.VISIBLE }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalFreshnessStatus).visibility)
        assertTrue(activity.findViewById<TextView>(R.id.timelineDataStatus).text.contains("detailed locations"))
        assertFalse(
            activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.journalReminderSwitch,
            ).isChecked,
        )
    }

    @Test
    fun reminderActionOpensTheJournalCardWithoutStartingAFilePicker() {
        val activity = launchActivity()
        requireNotNull(controller).newIntent(
            Intent(activity, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_JOURNAL),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
        assertTrue(activity.findViewById<View>(R.id.settingsImportTimelineButton).hasFocus())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
    }

    @Test
    fun dueJournalAppearsAsAnInAppLibraryCard() {
        val source = rawTimeline("due", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)
        ShadowDialog.getLatestDialog()?.dismiss()
        activity.findViewById<View>(R.id.navigationVideos).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalFreshnessCard).visibility)
        activity.findViewById<View>(R.id.journalFreshnessCard).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
    }

    @Test
    fun importPersistsFusedJournalAcrossActivityRecreationWithoutRememberedFile() {
        val activity = launchActivity()
        val source = Uri.fromFile(repoRoot().resolve("test-fixtures/semantic-and-raw-ranges.json"))

        activity.importTimeline(source)
        waitForImportedRoute(activity)
        val imported = activity.currentJourneyPoints()

        assertEquals(null, TimelineSourceStore(context).load())
        assertEquals(VideoDataSource.JOURNAL, activity.currentVideoDataSource())

        controller = requireNotNull(controller).recreate()
        val recreated = requireNotNull(controller).get()
        waitUntil { recreated.currentJourneyPoints() == imported }

        assertEquals(imported, recreated.currentJourneyPoints())
    }

    @Test
    fun rawOnlyImportIsAcceptedDirectlyAndEnablesAutomaticCreationChoices() {
        val source = rawTimeline("raw-only", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.createTypeStepGroup).visibility)
        assertTrue(activity.findViewById<View>(R.id.tripVideoChoice).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.recapVideoChoice).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.customRecapChoice).isEnabled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.rawSignalsDescription).visibility)
    }

    @Test
    fun nonOverlappingUpdateIsBlockedAndLeavesExistingRouteUnchanged() {
        val first = rawTimeline("first", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val different = rawTimeline("different", 48.8, 2.3, "2026-08-10T00:00:00Z")
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(first))
        waitForImportedRoute(activity)
        val before = activity.currentJourneyPoints()
        activity.importTimeline(Uri.fromFile(different))
        waitUntil {
            ShadowDialog.getLatestDialog()?.findViewById<TextView>(android.R.id.message)
                ?.text == activity.getString(R.string.journal_import_mismatch_message)
        }

        assertEquals(before, activity.currentJourneyPoints())
    }

    @Test
    fun failedUpdateKeepsPreviouslyLoadedJournalRoute() {
        val valid = rawTimeline("valid", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val malformed = File.createTempFile("malformed", ".json", context.cacheDir).apply {
            writeText("{not-json")
        }
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(valid))
        waitForImportedRoute(activity)
        val before = activity.currentJourneyPoints()
        activity.importTimeline(Uri.fromFile(malformed))
        waitUntil { activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE }

        assertEquals(before, activity.currentJourneyPoints())
    }

    @Test
    fun detailedObservationGapBecomesAConnectedInferredTransfer() {
        val source = File.createTempFile("gapped", ".json", context.cacheDir).apply {
            writeText(
                """
                {"rawSignals":[
                  {"position":{"LatLng":"geo:37.50,127.00","timestamp":"2026-08-01T00:00:00Z","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:37.51,127.01","timestamp":"2026-08-01T00:10:00Z","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:38.50,128.00","timestamp":"2026-08-01T01:00:00Z","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:38.51,128.01","timestamp":"2026-08-01T01:10:00Z","accuracyMeters":10}}
                ]}
                """.trimIndent(),
            )
        }
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)

        assertEquals(emptyList<Int>(), activity.currentJourneyBreakIndices())
        assertTrue(activity.currentJourneyDistanceKm() < 5.0)
    }

    @Test
    fun independentSemanticRecordsRemainConnectedForVideo() {
        val source = File.createTempFile("semantic-gap", ".json", context.cacheDir).apply {
            writeText(
                """
                {"semanticSegments":[
                  {"startTime":"2026-08-01T00:00:00Z","endTime":"2026-08-01T00:10:00Z","activity":{"start":{"latLng":"37.50,127.00"},"end":{"latLng":"37.51,127.01"}}},
                  {"startTime":"2026-08-01T01:00:00Z","endTime":"2026-08-01T01:10:00Z","activity":{"start":{"latLng":"38.50,128.00"},"end":{"latLng":"38.51,128.01"}}}
                ]}
                """.trimIndent(),
            )
        }
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)

        assertEquals(emptyList<Int>(), activity.currentJourneyBreakIndices())
        assertTrue(activity.currentJourneyDistanceKm() < 5.0)
    }

    private fun rawTimeline(name: String, latitude: Double, longitude: Double, start: String): File {
        val second = java.time.Instant.parse(start).plusSeconds(600)
        return File.createTempFile(name, ".json", context.cacheDir).apply {
            writeText(
                """
                {"rawSignals":[
                  {"position":{"LatLng":"geo:$latitude,$longitude","timestamp":"$start","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:${latitude + 0.01},${longitude + 0.01}","timestamp":"$second","accuracyMeters":10}}
                ]}
                """.trimIndent(),
            )
        }
    }

    private fun launchActivity(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return requireNotNull(controller).get()
    }

    private fun waitForImportedRoute(activity: MainActivity) {
        waitUntil {
            activity.currentJourneyPoints().size >= 2 &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("The asynchronous Journal operation did not finish")
    }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: error("Working directory unavailable")).absoluteFile
        while (!File(current, "settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Repository root unavailable")
        }
        return current
    }
}
