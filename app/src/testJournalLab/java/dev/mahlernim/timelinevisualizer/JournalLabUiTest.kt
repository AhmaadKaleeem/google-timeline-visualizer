package dev.mahlernim.timelinevisualizer

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalLabUiTest {
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("Travel Journal", context.getString(R.string.timeline_data))
        assertEquals("Grow Travel Journal", context.getString(R.string.import_or_update))
        assertTrue(context.getString(R.string.timeline_not_imported).contains("ready to grow"))
    }
}
