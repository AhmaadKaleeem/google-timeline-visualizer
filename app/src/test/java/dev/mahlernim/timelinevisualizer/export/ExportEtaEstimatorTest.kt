package dev.mahlernim.timelinevisualizer.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportEtaEstimatorTest {
    @Test
    fun withholdsEstimateUntilCreatingVideoSpeedIsStable() {
        val estimator = ExportEtaEstimator()

        assertNull(estimator.estimateRemainingSeconds(progress(1), 0L))
        assertNull(estimator.estimateRemainingSeconds(progress(25), 1_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(37), 2_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(61), 3_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(73), 4_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(97), 8_000L))

        assertNull(estimator.estimateRemainingSeconds(progress(121), 9_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(145), 10_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(169), 11_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(193), 12_000L))
        assertEquals(33, estimator.estimateRemainingSeconds(progress(217), 13_000L))
    }

    @Test
    fun stableEstimateCountsDownFromRecentFrameThroughput() {
        val estimator = ExportEtaEstimator()

        (0..8).forEach { second ->
            estimator.estimateRemainingSeconds(progress(1 + second * 24), second * 1_000L)
        }

        assertEquals(33, estimator.estimateRemainingSeconds(progress(217), 9_000L))
        assertEquals(32, estimator.estimateRemainingSeconds(progress(241), 10_000L))
        assertEquals(31, estimator.estimateRemainingSeconds(progress(265), 11_000L))
    }

    @Test
    fun hidesEstimateAgainWhenRecentSpeedChangesMaterially() {
        val estimator = ExportEtaEstimator()
        (0..9).forEach { second ->
            estimator.estimateRemainingSeconds(progress(1 + second * 24), second * 1_000L)
        }

        assertEquals(33, estimator.estimateRemainingSeconds(progress(217), 9_000L))
        assertNull(estimator.estimateRemainingSeconds(progress(229), 10_000L))
    }

    @Test
    fun ignoresPreparationAndFinishingPhases() {
        val estimator = ExportEtaEstimator(
            minimumObservationMillis = 0L,
            minimumSamples = 2,
            sampleWindowSize = 2,
        )

        assertNull(
            estimator.estimateRemainingSeconds(
                ExportProgress(0.05f, ExportPhase.PREPARING_MAP, 5, 10),
                0L,
            ),
        )
        assertNull(
            estimator.estimateRemainingSeconds(
                ExportProgress(0.95f, ExportPhase.FINISHING_VIDEO, 12, 36),
                1_000L,
            ),
        )
    }

    @Test
    fun resetsWhenASecondExportStarts() {
        val estimator = ExportEtaEstimator()
        (0..9).forEach { second ->
            estimator.estimateRemainingSeconds(progress(1 + second * 24), second * 1_000L)
        }
        assertEquals(33, estimator.estimateRemainingSeconds(progress(217), 9_000L))

        assertNull(estimator.estimateRemainingSeconds(progress(1), 10_000L))
    }

    private fun progress(completed: Int, total: Int = 1_000) = ExportProgress(
        fraction = 0.10f + 0.80f * completed / total,
        phase = ExportPhase.CREATING_VIDEO,
        completed = completed,
        total = total,
    )
}
