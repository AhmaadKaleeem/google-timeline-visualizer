package dev.mahlernim.timelinevisualizer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant

internal data class TimelineSourceFingerprint(
    val uri: String,
    val size: Long?,
    val lastModified: Long?,
)

/**
 * Stores normalized Timeline data in the app-private cache directory.
 *
 * Bump [PARSER_VERSION] whenever parser behavior changes the normalized output.
 * Cache reads are optional. Any missing, stale, or corrupt entry falls back to JSON parsing.
 */
internal class TimelineCache(context: Context) {
    private val cacheFile = AtomicFile(File(context.cacheDir, FILE_NAME))

    fun load(fingerprint: TimelineSourceFingerprint): ParsedTimeline? {
        if (!cacheFile.baseFile.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(cacheFile.openRead())).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Unexpected Timeline cache header")
                if (input.readInt() != FORMAT_VERSION) return null
                if (input.readInt() != PARSER_VERSION) return null
                if (input.readFingerprint() != fingerprint) return null

                val timelineCount = input.readInt().validatedCount(allowMissing = true)
                val timeline = if (timelineCount < 0) {
                    null
                } else {
                    Timeline(input.readGeoPoints(timelineCount))
                }
                val rawCount = input.readInt().validatedCount(allowMissing = false)
                val rawSignals = ArrayList<RawSignalPoint>(rawCount)
                repeat(rawCount) {
                    rawSignals += RawSignalPoint(
                        point = input.readGeoPoint(),
                        accuracyMeters = input.readDouble().also { value ->
                            if (!value.isFinite() || value < 0.0) {
                                throw IOException("Invalid cached location accuracy")
                            }
                        },
                    )
                }
                if (input.read() != -1) throw IOException("Unexpected trailing Timeline cache data")
                ParsedTimeline(timeline, rawSignals)
            }
        } catch (_: IOException) {
            clear()
            null
        } catch (_: RuntimeException) {
            clear()
            null
        }
    }

    fun store(fingerprint: TimelineSourceFingerprint, parsed: ParsedTimeline) {
        // The cache is disposable. Removing the previous entry first also keeps
        // replacement behavior consistent across Android and host-side tests.
        clear()
        val output = cacheFile.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(output))
            data.writeInt(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(PARSER_VERSION)
            data.writeFingerprint(fingerprint)
            data.writeGeoPoints(parsed.timeline?.points)
            data.writeInt(parsed.rawSignals.size)
            parsed.rawSignals.forEach { raw ->
                data.writeGeoPoint(raw.point)
                data.writeDouble(raw.accuracyMeters)
            }
            data.flush()
            cacheFile.finishWrite(output)
        } catch (error: Throwable) {
            cacheFile.failWrite(output)
            throw error
        }
    }

    fun clear() {
        cacheFile.delete()
    }

    private fun DataInputStream.readFingerprint(): TimelineSourceFingerprint = TimelineSourceFingerprint(
        uri = readSizedString(),
        size = readOptionalLong(),
        lastModified = readOptionalLong(),
    )

    private fun DataOutputStream.writeFingerprint(fingerprint: TimelineSourceFingerprint) {
        writeSizedString(fingerprint.uri)
        writeOptionalLong(fingerprint.size)
        writeOptionalLong(fingerprint.lastModified)
    }

    private fun DataInputStream.readGeoPoints(count: Int): List<GeoPoint> =
        ArrayList<GeoPoint>(count).also { points ->
            repeat(count) { points += readGeoPoint() }
        }

    private fun DataInputStream.readGeoPoint(): GeoPoint {
        val epochSecond = readLong()
        val nano = readInt()
        val latitude = readDouble()
        val longitude = readDouble()
        if (nano !in 0..999_999_999 || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.05112878..85.05112878 || longitude !in -180.0..180.0
        ) {
            throw IOException("Invalid cached Timeline point")
        }
        return GeoPoint(Instant.ofEpochSecond(epochSecond, nano.toLong()), latitude, longitude)
    }

    private fun DataOutputStream.writeGeoPoints(points: List<GeoPoint>?) {
        writeInt(points?.size ?: -1)
        points?.forEach { writeGeoPoint(it) }
    }

    private fun DataOutputStream.writeGeoPoint(point: GeoPoint) {
        writeLong(point.instant.epochSecond)
        writeInt(point.instant.nano)
        writeDouble(point.latitude)
        writeDouble(point.longitude)
    }

    private fun DataInputStream.readSizedString(): String {
        val size = readInt()
        if (size !in 0..MAX_URI_BYTES) throw IOException("Invalid Timeline cache URI length")
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeSizedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_URI_BYTES) { "Timeline source URI is too long" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readOptionalLong(): Long? = readLong().takeUnless { it == UNKNOWN_LONG }

    private fun DataOutputStream.writeOptionalLong(value: Long?) = writeLong(value ?: UNKNOWN_LONG)

    private fun Int.validatedCount(allowMissing: Boolean): Int {
        val minimum = if (allowMissing) -1 else 0
        if (this !in minimum..MAX_POINTS) throw IOException("Invalid Timeline cache point count")
        return this
    }

    companion object {
        private const val FILE_NAME = "timeline-v1.bin"
        private const val MAGIC = 0x544C4331
        private const val FORMAT_VERSION = 1
        private const val PARSER_VERSION = 1
        private const val UNKNOWN_LONG = Long.MIN_VALUE
        private const val MAX_URI_BYTES = 64 * 1024
        private const val MAX_POINTS = 10_000_000

        fun fingerprint(contentResolver: ContentResolver, uri: Uri): TimelineSourceFingerprint {
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val source = uri.path?.let(::File)
                return TimelineSourceFingerprint(
                    uri = uri.toString(),
                    size = source?.takeIf(File::isFile)?.length(),
                    lastModified = source?.takeIf(File::isFile)?.lastModified()?.takeIf { it > 0L },
                )
            }
            return TimelineSourceFingerprint(
                uri = uri.toString(),
                size = contentResolver.queryLong(uri, OpenableColumns.SIZE)?.takeIf { it >= 0L },
                lastModified = contentResolver.queryLong(
                    uri,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )?.takeIf { it > 0L },
            )
        }

        private fun ContentResolver.queryLong(uri: Uri, column: String): Long? = try {
            query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal class CachedTimelineLoader(context: Context) {
    private val contentResolver = context.contentResolver
    private val cache = TimelineCache(context)

    fun load(uri: Uri, useCache: Boolean): ParsedTimeline {
        val fingerprint = TimelineCache.fingerprint(contentResolver, uri)
        if (useCache) cache.load(fingerprint)?.let { return it }

        val parsed = contentResolver.openInputStream(uri)?.use(TimelineParser()::parseWithRawSignals)
            ?: throw FileNotFoundException()
        try {
            cache.store(fingerprint, parsed)
        } catch (_: Exception) {
            // Cache writes are optional. The parsed Timeline remains usable.
        }
        return parsed
    }
}
