package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Stable storage codec for semantic path geometry retained by Journal imports. */
object SemanticGeometryCodec {
    fun encode(points: List<GeoPoint>): String = buildString {
        append('[')
        points.forEachIndexed { index, point ->
            if (index > 0) append(',')
            append("{\"instantEpochMillis\":")
            append(point.instant.toEpochMilli())
            append(",\"latitude\":")
            append(point.latitude)
            append(",\"longitude\":")
            append(point.longitude)
            append('}')
        }
        append(']')
    }

    fun decode(value: String?): List<GeoPoint> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val trimmed = value.trim()
            val values = if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optJSONArray("points") ?: JSONArray()
            } else {
                JSONArray(trimmed)
            }
            buildList {
                for (index in 0 until values.length()) {
                    decodePoint(values.opt(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodePoint(value: Any?): GeoPoint? {
        val decoded = when (value) {
            is JSONObject -> {
                val epochMillis = value.longOrNull("instantEpochMillis", "epochMillis", "timestamp")
                val latitude = value.doubleOrNull("latitude", "lat")
                val longitude = value.doubleOrNull("longitude", "lng", "lon")
                Triple(epochMillis, latitude, longitude)
            }
            is JSONArray -> Triple(
                value.numberOrNull(0)?.toLong(),
                value.numberOrNull(1)?.toDouble(),
                value.numberOrNull(2)?.toDouble(),
            )
            else -> return null
        }
        val epochMillis = decoded.first ?: return null
        val latitude = decoded.second ?: return null
        val longitude = decoded.third ?: return null
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        return GeoPoint(Instant.ofEpochMilli(epochMillis), latitude, longitude)
    }

    private fun JSONObject.longOrNull(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
        if (!has(name) || isNull(name)) null else opt(name).asNumber()?.toLong()
    }

    private fun JSONObject.doubleOrNull(vararg names: String): Double? = names.firstNotNullOfOrNull { name ->
        if (!has(name) || isNull(name)) null else opt(name).asNumber()?.toDouble()
    }

    private fun JSONArray.numberOrNull(index: Int): Number? = opt(index).asNumber()

    private fun Any?.asNumber(): Number? = when (this) {
        is Number -> this
        is String -> toDoubleOrNull()
        else -> null
    }
}
