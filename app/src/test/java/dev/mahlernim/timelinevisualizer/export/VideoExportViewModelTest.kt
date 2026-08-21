package dev.mahlernim.timelinevisualizer.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoExportViewModelTest {
    @Test
    fun restoresOnceAndDelegatesStateChanges() {
        val gateway = FakeGateway()
        val viewModel = VideoExportViewModel(gateway)
        val running = VideoExportSnapshot(status = VideoExportStatus.RUNNING, title = "Trip")

        assertEquals(1, gateway.restoreCount)
        viewModel.publish(running)
        assertEquals(running, viewModel.current)
        viewModel.clear()
        assertEquals(VideoExportSnapshot(), viewModel.current)
    }

    private class FakeGateway : VideoExportStateGateway {
        private val mutableState = MutableStateFlow(VideoExportSnapshot())
        override val state: StateFlow<VideoExportSnapshot> = mutableState
        var restoreCount = 0

        override fun restore() {
            restoreCount += 1
        }

        override fun publish(snapshot: VideoExportSnapshot) {
            mutableState.value = snapshot
        }

        override fun clear() {
            mutableState.value = VideoExportSnapshot()
        }
    }
}
