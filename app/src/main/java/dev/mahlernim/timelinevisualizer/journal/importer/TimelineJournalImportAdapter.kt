package dev.mahlernim.timelinevisualizer.journal.importer

import dev.mahlernim.timelinevisualizer.data.TimelineParser
import dev.mahlernim.timelinevisualizer.journal.DetailedObservationInput
import dev.mahlernim.timelinevisualizer.journal.JournalImport
import dev.mahlernim.timelinevisualizer.journal.JournalMatchClassification
import dev.mahlernim.timelinevisualizer.journal.SemanticSegmentInput
import dev.mahlernim.timelinevisualizer.journal.route.SemanticGeometryCodec
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Converts the currently supported Timeline export into the durable Journal import contract.
 *
 * The v2 parser exposes detailed positions with accuracy, but exposes semantic data only as a
 * normalized point sequence. Until segment-aware parsing lands, the adapter deliberately leaves
 * activity and place metadata empty and records only the geometry that the parser can prove.
 */
class TimelineJournalImportAdapter(
    private val parser: TimelineParser = TimelineParser(),
) {
    fun adapt(
        input: InputStream,
        sourceName: String?,
        importedAtEpochMillis: Long,
        matchClassification: JournalMatchClassification,
    ): JournalImport {
        val digest = MessageDigest.getInstance(SHA_256)
        val countingInput = CountingInputStream(input)
        val digestInput = DigestInputStream(countingInput, digest)
        val parsed = parser.parseWithRawSignals(NonClosingInputStream(digestInput))

        // JsonReader may finish after the root value while buffered source bytes remain. Consume
        // them before finalizing so the hash and size describe the exact selected document.
        val drainBuffer = ByteArray(DRAIN_BUFFER_BYTES)
        while (digestInput.read(drainBuffer) != -1) {
            // Drain only. DigestInputStream updates the hash as bytes pass through it.
        }

        return JournalImport(
            sourceHash = digest.digest().toHexString(),
            sourceName = sourceName,
            sourceSize = countingInput.byteCount,
            importedAtEpochMillis = importedAtEpochMillis,
            parserVersion = PARSER_VERSION,
            matchClassification = matchClassification,
            detailedObservations = parsed.rawSignals.map { rawSignal ->
                DetailedObservationInput(
                    instantEpochMillis = rawSignal.point.instant.toEpochMilli(),
                    latitude = rawSignal.point.latitude,
                    longitude = rawSignal.point.longitude,
                    accuracyMeters = rawSignal.accuracyMeters,
                )
            },
            semanticSegments = parsed.timeline?.points.orEmpty()
                .chunked(MAX_SEMANTIC_POINTS_PER_SEGMENT)
                .map(::semanticPathSegment),
        )
    }

    private fun semanticPathSegment(points: List<GeoPoint>): SemanticSegmentInput =
        SemanticSegmentInput(
            startEpochMillis = points.first().instant.toEpochMilli(),
            endEpochMillis = points.last().instant.toEpochMilli(),
            kind = SEMANTIC_PATH_KIND,
            geometryJson = SemanticGeometryCodec.encode(points),
        )

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var byteCount: Long = 0
            private set

        override fun read(): Int = super.read().also { value ->
            if (value != -1) byteCount += 1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) byteCount += count
            }
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    companion object {
        const val PARSER_VERSION = 1
        const val SEMANTIC_PATH_KIND = "TIMELINE_PATH"
        const val MAX_SEMANTIC_POINTS_PER_SEGMENT = 10_000
        private const val DRAIN_BUFFER_BYTES = 8 * 1024
        private const val SHA_256 = "SHA-256"
    }
}
