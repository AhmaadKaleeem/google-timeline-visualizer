package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.Journey
import kotlin.math.pow

/** Maps elapsed video progress to original route distance without changing route geometry. */
class JourneyTiming private constructor(
    private val elapsedFractions: DoubleArray,
    private val distancesKm: DoubleArray,
    private val slopes: DoubleArray,
    private val linearDistanceKm: Double?,
) {
    fun distanceAt(progress: Float): Double {
        val elapsed = progress.coerceIn(0f, 1f).toDouble()
        linearDistanceKm?.let { return it * elapsed }
        if (elapsedFractions.size < 2) return distancesKm.lastOrNull() ?: 0.0
        val exact = elapsedFractions.binarySearch(elapsed)
        if (exact >= 0) return distancesKm[exact]
        val to = (-exact - 1).coerceIn(1, elapsedFractions.lastIndex)
        val from = to - 1
        val width = elapsedFractions[to] - elapsedFractions[from]
        if (width <= 0.0) return distancesKm[from]
        val t = ((elapsed - elapsedFractions[from]) / width).coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * distancesKm[from] +
            (t3 - 2 * t2 + t) * width * slopes[from] +
            (-2 * t3 + 3 * t2) * distancesKm[to] +
            (t3 - t2) * width * slopes[to]
    }

    companion object {
        fun create(journey: Journey, compression: LongTripCompression): JourneyTiming {
            if (compression == LongTripCompression.OFF || journey.points.size < 2) {
                return JourneyTiming(doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), journey.totalDistanceKm)
            }
            val distances = ArrayList<Double>(journey.cumulativeDistanceKm.size)
            val effective = ArrayList<Double>(journey.cumulativeDistanceKm.size)
            distances += 0.0
            effective += 0.0
            var effectiveTotal = 0.0
            for (index in 1..journey.cumulativeDistanceKm.lastIndex) {
                val segmentKm = journey.cumulativeDistanceKm[index] - journey.cumulativeDistanceKm[index - 1]
                if (segmentKm <= 0.0) continue
                effectiveTotal += segmentKm.pow(compression.exponent)
                distances += journey.cumulativeDistanceKm[index]
                effective += effectiveTotal
            }
            if (effectiveTotal <= 0.0 || distances.size < 2) {
                return JourneyTiming(doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), journey.totalDistanceKm)
            }
            val x = DoubleArray(effective.size) { effective[it] / effectiveTotal }
            val y = distances.toDoubleArray()
            return JourneyTiming(x, y, monotoneSlopes(x, y), null)
        }

        private fun monotoneSlopes(x: DoubleArray, y: DoubleArray): DoubleArray {
            val segmentCount = x.size - 1
            val delta = DoubleArray(segmentCount) { index ->
                (y[index + 1] - y[index]) / (x[index + 1] - x[index])
            }
            if (segmentCount == 1) return doubleArrayOf(delta[0], delta[0])
            val slopes = DoubleArray(x.size)
            slopes[0] = endpointSlope(x[1] - x[0], x[2] - x[1], delta[0], delta[1])
            for (index in 1 until x.lastIndex) {
                if (delta[index - 1] <= 0.0 || delta[index] <= 0.0) {
                    slopes[index] = 0.0
                } else {
                    val beforeWidth = x[index] - x[index - 1]
                    val afterWidth = x[index + 1] - x[index]
                    val weightBefore = 2 * afterWidth + beforeWidth
                    val weightAfter = afterWidth + 2 * beforeWidth
                    slopes[index] = (weightBefore + weightAfter) /
                        (weightBefore / delta[index - 1] + weightAfter / delta[index])
                }
            }
            slopes[slopes.lastIndex] = endpointSlope(
                x[x.lastIndex] - x[x.lastIndex - 1],
                x[x.lastIndex - 1] - x[x.lastIndex - 2],
                delta[delta.lastIndex],
                delta[delta.lastIndex - 1],
            )
            return slopes
        }

        private fun endpointSlope(firstWidth: Double, secondWidth: Double, first: Double, second: Double): Double {
            val slope = ((2 * firstWidth + secondWidth) * first - firstWidth * second) /
                (firstWidth + secondWidth)
            return when {
                slope <= 0.0 -> 0.0
                slope > 3 * first -> 3 * first
                else -> slope
            }
        }
    }
}
