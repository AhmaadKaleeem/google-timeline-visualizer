package dev.mahlernim.timelinevisualizer.videos

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoLibraryViewModel(
    private val repository: VideoRecordRepository,
) : ViewModel() {
    private val mutableRecords = MutableStateFlow(repository.list())
    val records: StateFlow<List<VideoRecord>> = mutableRecords.asStateFlow()

    fun refresh() {
        mutableRecords.value = repository.list()
    }

    fun upsert(record: VideoRecord) {
        repository.upsert(record)
        refresh()
    }

    fun remove(record: VideoRecord) {
        repository.remove(record.uri)
        refresh()
    }

    fun removeAll(records: Collection<VideoRecord>) {
        repository.removeAll(records.mapTo(mutableSetOf(), VideoRecord::uri))
        refresh()
    }
}
