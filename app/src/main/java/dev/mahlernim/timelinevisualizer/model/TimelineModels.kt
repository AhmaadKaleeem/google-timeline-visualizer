package dev.mahlernim.timelinevisualizer.model

import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class GeoPoint(
    val instant: Instant,
    val latitude: Double,
    val longitude: Double,
) {
    val year: Int get() = instant.atZone(ZoneId.systemDefault()).year
}

data class Timeline(
    val points: List<GeoPoint>,
) {
    val years: List<Int> = points.map { it.year }.distinct().sortedDescending()

    fun forYear(year: Int): Journey {
        val selected = points.filter { it.year == year }.sortedBy { it.instant }
        return Journey.from(selected, year)
    }
}

data class Journey(
    val year: Int,
    val points: List<GeoPoint>,
    val cumulativeDistanceKm: DoubleArray,
) {
    val totalDistanceKm: Double get() = cumulativeDistanceKm.lastOrNull() ?: 0.0

    fun pointIndexAt(progress: Float): Int {
        if (points.isEmpty()) return 0
        val target = totalDistanceKm * progress.coerceIn(0f, 1f)
        val position = cumulativeDistanceKm.binarySearch(target)
        return if (position >= 0) position else min(-position - 1, points.lastIndex)
    }

    companion object {
        fun from(points: List<GeoPoint>, year: Int): Journey {
            val distances = DoubleArray(points.size)
            for (index in 1 until points.size) {
                distances[index] = distances[index - 1] + haversineKm(points[index - 1], points[index])
            }
            return Journey(year, points, distances)
        }

        private fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 6371.0088 * 2 * asin(min(1.0, sqrt(h)))
        }
    }
}

data class WorldPoint(val x: Double, val y: Double)

object WebMercator {
    private const val MAX_LATITUDE = 85.05112878

    fun project(point: GeoPoint): WorldPoint = project(point.latitude, point.longitude)

    fun project(latitude: Double, longitude: Double): WorldPoint {
        val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = (longitude + 180.0) / 360.0
        val sinLat = sin(Math.toRadians(lat))
        val y = 0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)
        return WorldPoint(x, y.coerceIn(0.0, 1.0))
    }

    fun shortestWrappedX(values: List<Double>): List<Double> {
        if (values.size < 2) return values
        val directSpan = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
        val shifted = values.map { if (it < 0.5) it + 1.0 else it }
        val shiftedSpan = (shifted.maxOrNull() ?: 0.0) - (shifted.minOrNull() ?: 0.0)
        return if (shiftedSpan < directSpan) shifted else values
    }
}
