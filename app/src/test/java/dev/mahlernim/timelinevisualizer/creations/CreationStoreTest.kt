package dev.mahlernim.timelinevisualizer.creations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CreationStore(context).also(CreationStore::clear)

    @After
    fun tearDown() = store.clear()

    @Test
    fun savesNewestFirstAndRestoresOptionalTimelineDetails() {
        store.upsert(record("content://old", "Old", 100L))
        store.upsert(
            record("content://new", "New", 200L).copy(year = 2026, startMonth = 3, endMonth = 8),
        )

        val restored = CreationStore(context).list()
        assertEquals(listOf("New", "Old"), restored.map(CreationRecord::title))
        assertEquals(2026, restored.first().year)
        assertEquals(3, restored.first().startMonth)
        assertEquals(8, restored.first().endMonth)
    }

    @Test
    fun upsertReplacesMatchingUriAndRemoveForgetsOnlyThatRecord() {
        store.upsert(record("content://same", "First", 100L))
        store.upsert(record("content://other", "Other", 150L))
        store.upsert(record("content://same", "Updated", 200L))

        assertEquals(listOf("Updated", "Other"), store.list().map(CreationRecord::title))
        store.remove("content://same")
        assertEquals(listOf("Other"), store.list().map(CreationRecord::title))
    }

    private fun record(uri: String, title: String, createdAt: Long) = CreationRecord(
        uri = uri,
        title = title,
        fileName = "$title.mp4",
        createdAtMillis = createdAt,
        durationSeconds = 30,
    )
}
