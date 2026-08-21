package dev.mahlernim.timelinevisualizer.export

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil

internal class ExportEtaEstimator(
    private val minimumObservationMillis: Long = 8_000L,
    private val minimumSamples: Int = 5,
    private val sampleWindowSize: Int = 6,
    private val stabilityTolerance: Double = 0.15,
) {
    private data class Sample(
        val elapsedMillis: Long,
        val completed: Int,
    )

    private val samples = ArrayDeque<Sample>()
    private var phaseStartedAtMillis: Long? = null
    private var phaseTotal: Int? = null
    private var lastEstimateSeconds: Int? = null

    fun estimateRemainingSeconds(progress: ExportProgress, elapsedMillis: Long): Int? {
        if (
            progress.phase != ExportPhase.CREATING_VIDEO ||
            progress.total <= 0 ||
            progress.completed <= 0 ||
            progress.completed >= progress.total
        ) {
            reset()
            return null
        }

        val previous = samples.peekLast()
        if (
            phaseTotal != null && phaseTotal != progress.total ||
            previous != null && (
                elapsedMillis < previous.elapsedMillis ||
                    progress.completed < previous.completed
                )
        ) {
            reset()
        }

        if (phaseStartedAtMillis == null) {
            phaseStartedAtMillis = elapsedMillis
            phaseTotal = progress.total
        }

        val currentPrevious = samples.peekLast()
        if (currentPrevious?.completed == progress.completed) return lastEstimateSeconds

        samples.addLast(Sample(elapsedMillis, progress.completed))
        while (samples.size > sampleWindowSize) samples.removeFirst()

        val observedMillis = elapsedMillis - (phaseStartedAtMillis ?: elapsedMillis)
        if (observedMillis < minimumObservationMillis || samples.size < minimumSamples) {
            lastEstimateSeconds = null
            return null
        }

        val rates = samples.zipWithNext { first, second ->
            val duration = second.elapsedMillis - first.elapsedMillis
            val completed = second.completed - first.completed
            if (duration <= 0L || completed <= 0) null else completed.toDouble() / duration
        }
        if (rates.any { it == null }) {
            lastEstimateSeconds = null
            return null
        }

        val measuredRates = rates.filterNotNull()
        val medianRate = measuredRates.sorted()[measuredRates.size / 2]
        val stable = medianRate > 0.0 && measuredRates.all { rate ->
            abs(rate - medianRate) / medianRate <= stabilityTolerance
        }
        if (!stable) {
            lastEstimateSeconds = null
            return null
        }

        val first = requireNotNull(samples.peekFirst())
        val last = requireNotNull(samples.peekLast())
        val windowMillis = last.elapsedMillis - first.elapsedMillis
        val windowCompleted = last.completed - first.completed
        if (windowMillis <= 0L || windowCompleted <= 0) return null

        val framesPerMillis = windowCompleted.toDouble() / windowMillis
        val remainingFrames = progress.total - progress.completed
        return ceil(remainingFrames / framesPerMillis / 1_000.0)
            .toInt()
            .coerceAtLeast(1)
            .also { lastEstimateSeconds = it }
    }

    fun reset() {
        samples.clear()
        phaseStartedAtMillis = null
        phaseTotal = null
        lastEstimateSeconds = null
    }
}
