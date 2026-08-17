package dev.mahlernim.timelinevisualizer.render

data class TimelineFrame(
    val journeyProgress: Float,
    val outroProgress: Float,
)

object TimelineAnimation {
    const val OUTRO_SECONDS = 1.5f
    const val OUTRO_TRANSITION_SECONDS = 1.0f

    fun totalDurationSeconds(journeyDurationSeconds: Int): Float =
        journeyDurationSeconds.coerceAtLeast(1) + OUTRO_SECONDS

    fun frameAtOverallProgress(overallProgress: Float, journeyDurationSeconds: Int): TimelineFrame {
        val journeySeconds = journeyDurationSeconds.coerceAtLeast(1).toFloat()
        val elapsedSeconds = overallProgress.coerceIn(0f, 1f) * totalDurationSeconds(journeyDurationSeconds)
        return frameAtElapsedSeconds(elapsedSeconds, journeyDurationSeconds)
    }

    fun frameAtElapsedSeconds(elapsedSeconds: Float, journeyDurationSeconds: Int): TimelineFrame {
        val journeySeconds = journeyDurationSeconds.coerceAtLeast(1).toFloat()
        if (elapsedSeconds <= journeySeconds) {
            return TimelineFrame((elapsedSeconds / journeySeconds).coerceIn(0f, 1f), 0f)
        }
        val outroElapsed = elapsedSeconds - journeySeconds
        return TimelineFrame(
            journeyProgress = 1f,
            outroProgress = (outroElapsed / OUTRO_TRANSITION_SECONDS).coerceIn(0f, 1f),
        )
    }
}
