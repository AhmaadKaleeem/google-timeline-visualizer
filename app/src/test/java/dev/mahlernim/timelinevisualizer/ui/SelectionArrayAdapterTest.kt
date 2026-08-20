package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SelectionArrayAdapterTest {
    @Test
    fun displayedSelectionNeverNarrowsAvailableChoices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = SelectionArrayAdapter(context, listOf("One", "Two", "Three"))
        val completed = CountDownLatch(1)

        adapter.filter.filter("Two") { completed.countDown() }

        assertEquals(true, completed.await(5, TimeUnit.SECONDS))
        assertEquals(3, adapter.count)
        assertEquals(listOf("One", "Two", "Three"), (0 until adapter.count).map(adapter::getItem))
    }
}
