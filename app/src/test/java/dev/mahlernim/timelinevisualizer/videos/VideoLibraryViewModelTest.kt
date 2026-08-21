package dev.mahlernim.timelinevisualizer.videos

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibraryViewModelTest {
    @Test
    fun mutationsRefreshThePublishedCreationList() {
        val repository = FakeRepository()
        val viewModel = VideoLibraryViewModel(repository)
        val first = record("content://videos/first", 1L)
        val second = record("content://videos/second", 2L)

        viewModel.upsert(first)
        viewModel.upsert(second)
        assertEquals(listOf(second, first), viewModel.records.value)

        viewModel.remove(second)
        assertEquals(listOf(first), viewModel.records.value)

        viewModel.removeAll(listOf(first))
        assertEquals(emptyList<VideoRecord>(), viewModel.records.value)
    }

    private fun record(uri: String, createdAt: Long) = VideoRecord(
        uri = uri,
        title = uri.substringAfterLast('/'),
        fileName = "video.mp4",
        createdAtMillis = createdAt,
        durationSeconds = 10,
    )

    private class FakeRepository : VideoRecordRepository {
        private val records = mutableListOf<VideoRecord>()

        override fun list(): List<VideoRecord> = records.sortedByDescending(VideoRecord::createdAtMillis)

        override fun upsert(record: VideoRecord) {
            records.removeAll { it.uri == record.uri }
            records += record
        }

        override fun remove(uri: String) {
            records.removeAll { it.uri == uri }
        }

        override fun removeAll(uris: Set<String>) {
            records.removeAll { it.uri in uris }
        }
    }
}
