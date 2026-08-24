package dev.mahlernim.timelinevisualizer.journal.importer

import dev.mahlernim.timelinevisualizer.data.TimelineParser
import dev.mahlernim.timelinevisualizer.data.StructuredSemanticSegment
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
 * Detailed positions retain accuracy. Semantic source records retain their own boundaries and
 * metadata, while oversized geometry is split only with explicit same-record continuity metadata.
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
            semanticSegments = if (parsed.semanticSegments.isNotEmpty()) {
                parsed.semanticSegments.flatMap(::structuredSemanticParts)
            } else {
                fallbackSemanticParts(parsed.timeline?.points.orEmpty())
            },
        )
    }

    private fun structuredSemanticParts(segment: StructuredSemanticSegment): List<SemanticSegmentInput> {
        if (segment.geometry.isEmpty()) return emptyList()
        val parts = segment.geometry.chunked(MAX_SEMANTIC_POINTS_PER_SEGMENT)
        val group = "source:${segment.sourceOrdinal}"
        return parts.mapIndexed { index, points ->
            SemanticSegmentInput(
                startEpochMillis = when (index) {
                    0 -> minOf(
                        segment.start?.toEpochMilli() ?: points.first().instant.toEpochMilli(),
                        points.first().instant.toEpochMilli(),
                    )
                    else -> points.first().instant.toEpochMilli()
                },
                endEpochMillis = when (index) {
                    parts.lastIndex -> maxOf(
                        segment.end?.toEpochMilli() ?: points.last().instant.toEpochMilli(),
                        points.last().instant.toEpochMilli(),
                    )
                    else -> points.last().instant.toEpochMilli()
                },
                kind = segment.kind.name,
                activityType = segment.activityType,
                placeId = segment.placeId,
                geometryJson = SemanticGeometryCodec.encodePart(
                    points = points,
                    continuityGroup = group,
                    partIndex = index,
                    partCount = parts.size,
                ),
            )
        }
    }

    /** Compatibility fallback for a supported parser shape that has no structured records. */
    private fun fallbackSemanticParts(points: List<GeoPoint>): List<SemanticSegmentInput> {
        val parts = points.chunked(MAX_SEMANTIC_POINTS_PER_SEGMENT)
        return parts.mapIndexed { index, part ->
            SemanticSegmentInput(
                startEpochMillis = part.first().instant.toEpochMilli(),
                endEpochMillis = part.last().instant.toEpochMilli(),
                kind = SEMANTIC_PATH_KIND,
                geometryJson = SemanticGeometryCodec.encodePart(
                    points = part,
                    continuityGroup = FALLBACK_CONTINUITY_GROUP,
                    partIndex = index,
                    partCount = parts.size,
                ),
            )
        }
    }

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
        const val PARSER_VERSION = 2
        const val SEMANTIC_PATH_KIND = "TIMELINE_PATH"
        const val MAX_SEMANTIC_POINTS_PER_SEGMENT = 10_000
        private const val DRAIN_BUFFER_BYTES = 8 * 1024
        private const val SHA_256 = "SHA-256"
        private const val FALLBACK_CONTINUITY_GROUP = "fallback-timeline"
    }
}
