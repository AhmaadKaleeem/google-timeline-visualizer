package dev.mahlernim.timelinevisualizer.export

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoExportServiceTest {
    @Test
    fun usesMediaProcessingTypeOnAndroid15AndNewer() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            VideoExportService.foregroundServiceTypeForApi(35),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            VideoExportService.foregroundServiceTypeForApi(36),
        )
    }

    @Test
    fun usesRecognizedDataSyncCompatibilityTypeOnOlderAndroid() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            VideoExportService.foregroundServiceTypeForApi(34),
        )
    }
}
