package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.creations.CreationRecord
import dev.mahlernim.timelinevisualizer.creations.CreationStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CreationStore(context)
    private lateinit var controller: ActivityController<MainActivity>

    @Before
    fun setUp() {
        store.clear()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
        store.clear()
    }

    @Test
    fun savedCreationAppearsAfterActivityRestart() {
        store.upsert(
            CreationRecord(
                uri = "content://example/video",
                title = "2026 Mina's Timeline",
                fileName = "timeline.mp4",
                createdAtMillis = 1_786_900_000_000L,
                durationSeconds = 30,
                year = 2026,
                startMonth = 1,
                endMonth = 12,
            ),
        )

        val activity = launchActivity()
        val list = activity.findViewById<LinearLayout>(R.id.creationsList)

        assertEquals(1, list.childCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyCreationsText).visibility)
        assertEquals(
            "2026 Mina's Timeline",
            list.getChildAt(0).findViewById<android.widget.TextView>(R.id.creationTitle).text.toString(),
        )
    }

    @Test
    fun checkForUpdatesOpensOfficialLatestRelease() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.checkUpdatesButton).performClick()

        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            "https://github.com/mahlernim/google-timeline-visualizer/releases/latest",
            intent.dataString,
        )
    }

    @Test
    fun longCreationListStartsCompactAndCanExpand() {
        repeat(4) { index ->
            store.upsert(
                CreationRecord(
                    uri = "content://example/video/$index",
                    title = "Video $index",
                    fileName = "video-$index.mp4",
                    createdAtMillis = index.toLong(),
                    durationSeconds = 30,
                ),
            )
        }

        val activity = launchActivity()
        val list = activity.findViewById<LinearLayout>(R.id.creationsList)
        val showAll = activity.findViewById<View>(R.id.showAllCreationsButton)

        assertEquals(3, list.childCount)
        assertEquals(View.VISIBLE, showAll.visibility)
        showAll.performClick()
        assertEquals(4, list.childCount)
    }

    private fun launchActivity(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return controller.get()
    }
}
