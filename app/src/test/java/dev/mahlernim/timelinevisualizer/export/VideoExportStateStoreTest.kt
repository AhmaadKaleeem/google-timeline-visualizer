package dev.mahlernim.timelinevisualizer.export

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
class VideoExportStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = VideoExportStateStore(context).also(VideoExportStateStore::clear)

    @After
    fun tearDown() {
        store.clear()
        VideoExportCoordinator.resetForTest()
    }

    @Test
    fun runningProgressSurvivesCoordinatorRecreation() {
        val expected = VideoExportSnapshot(
            status = VideoExportStatus.RUNNING,
            progress = ExportProgress(0.42f, ExportPhase.CREATING_VIDEO, 42, 100),
            startedAtMillis = 1_786_900_000_000L,
            outputUri = "content://documents/timeline.mp4",
            title = "2026 Mina's Timeline",
        )
        store.save(expected)

        VideoExportCoordinator.resetForTest()
        VideoExportCoordinator.restore(context)

        assertEquals(expected, VideoExportCoordinator.state.value)
    }

    @Test
    fun clearReturnsExportToIdle() {
        store.save(VideoExportSnapshot(status = VideoExportStatus.COMPLETE))
        VideoExportCoordinator.resetForTest()
        VideoExportCoordinator.restore(context)

        VideoExportCoordinator.clear(context)

        assertEquals(VideoExportStatus.IDLE, VideoExportCoordinator.state.value.status)
        assertEquals(VideoExportStatus.IDLE, store.load().status)
    }

    @Test
    fun finishingProgressSurvivesCoordinatorRecreation() {
        val expected = VideoExportSnapshot(
            status = VideoExportStatus.RUNNING,
            progress = ExportProgress(0.99f, ExportPhase.FINISHING_VIDEO, 12, 36),
            startedAtMillis = 1_786_900_000_000L,
        )
        store.save(expected)

        VideoExportCoordinator.resetForTest()
        VideoExportCoordinator.restore(context)

        assertEquals(expected, VideoExportCoordinator.state.value)
    }

    @Test
    fun failureKindSurvivesCoordinatorRecreation() {
        val expected = VideoExportSnapshot(
            status = VideoExportStatus.FAILED,
            errorMessage = "Free storage",
            failureKind = VideoExportFailureKind.STORAGE_FULL,
        )
        store.save(expected)

        VideoExportCoordinator.resetForTest()
        VideoExportCoordinator.restore(context)

        assertEquals(expected, VideoExportCoordinator.state.value)
    }

    @Test
    fun legacyFailedStateDefaultsToUnknown() {
        store.save(
            VideoExportSnapshot(
                status = VideoExportStatus.FAILED,
                errorMessage = "Could not create video",
            ),
        )

        assertEquals(VideoExportFailureKind.UNKNOWN, store.load().failureKind)
    }
}
