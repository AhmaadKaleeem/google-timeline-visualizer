package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.VideoDuration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

internal object DurationRecommendation {
    fun recommend(
        frames: List<TimelinePainter.CameraFrame>,
        aspect: Double,
        largeTransferCount: Int,
    ): Int {
        if (frames.size < 2 || !aspect.isFinite() || aspect <= 0.0) return VideoDuration.DEFAULT_SECONDS
        var movementWork = 0.0
        var zoomWork = 0.0
        for (index in 1 until frames.size) {
            val previous = frames[index - 1]
            val current = frames[index]
            val spanY = sqrt(previous.spanY * current.spanY).coerceAtLeast(MIN_SPAN)
            val spanX = (spanY * aspect).coerceAtLeast(MIN_SPAN)
            movementWork += hypot(
                wrappedDelta(current.centerX - previous.centerX) / spanX,
                (current.centerY - previous.centerY) / spanY,
            )
            zoomWork += abs(ln(current.spanY / previous.spanY) / LN_2)
        }
        val journeySeconds = movementWork / TARGET_VIEWPORTS_PER_SECOND +
            zoomWork / TARGET_ZOOM_LEVELS_PER_SECOND +
            largeTransferCount.coerceAtLeast(0) * SECONDS_PER_TRANSFER
        return (ceil((TimelineAnimation.OUTRO_SECONDS + journeySeconds) / ROUNDING_SECONDS) * ROUNDING_SECONDS)
            .toInt()
            .coerceIn(VideoDuration.MIN_SECONDS, VideoDuration.MAX_SECONDS)
    }

    private fun wrappedDelta(delta: Double): Double = when {
        delta > 0.5 -> delta - 1.0
        delta < -0.5 -> delta + 1.0
        else -> delta
    }

    private const val MIN_SPAN = 1e-9
    private const val LN_2 = 0.6931471805599453
    private const val TARGET_VIEWPORTS_PER_SECOND = 0.9
    private const val TARGET_ZOOM_LEVELS_PER_SECOND = 1.5
    private const val SECONDS_PER_TRANSFER = 1.5
    private const val ROUNDING_SECONDS = 5.0
}
