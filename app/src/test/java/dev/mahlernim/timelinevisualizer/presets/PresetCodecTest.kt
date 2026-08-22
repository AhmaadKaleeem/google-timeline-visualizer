package dev.mahlernim.timelinevisualizer.presets

import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PresetCodecTest {
    private val values = PresetValues(
        VideoAspectRatio.PORTRAIT,
        CameraMovement.CLOSE_UP,
        TripDetection.SENSITIVE,
        LocalFraming.CLOSE,
        LongTripCompression.STRONGER,
    )

    @Test
    fun compactTokenRoundTripsEveryPresetField() {
        val token = PresetCodec.encode(values)

        assertTrue(token.length <= PresetCodec.MAX_TOKEN_LENGTH)
        assertEquals(PresetDecodeResult.Success(values), PresetCodec.decode(token))
    }

    @Test
    fun applyingPresetPreservesResolution() {
        val draft = CameraSettings(videoQuality = VideoQuality.LANDSCAPE_720)

        val applied = values.applyTo(draft)

        assertEquals(VideoAspectRatio.PORTRAIT, applied.videoQuality.aspectRatioOption)
        assertEquals(VideoQuality.PORTRAIT_720, applied.videoQuality)
        assertEquals(values, PresetValues.from(applied))
    }

    @Test
    fun decoderToleratesBoundedFutureBytesAndReservedBits() {
        val original = Base64.getUrlDecoder().decode(PresetCodec.encode(values))
        original[2] = (original[2].toInt() or 0b11110000).toByte()
        val extended = original + byteArrayOf(7, 8)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(extended)

        assertEquals(PresetDecodeResult.Success(values), PresetCodec.decode(token))
    }

    @Test
    fun invalidAndUnsupportedTokensAreRejectedWithoutGuessing() {
        assertEquals(PresetDecodeResult.Invalid, PresetCodec.decode(""))
        assertEquals(PresetDecodeResult.Invalid, PresetCodec.decode("not+urlsafe"))
        val unsupported = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xA2.toByte(), 0, 0))
        assertEquals(PresetDecodeResult.Unsupported, PresetCodec.decode(unsupported))
    }

    @Test
    fun httpsLinkContainsOnlyTheCompactToken() {
        val link = PresetLink.create(values)

        assertEquals(PresetDecodeResult.Success(values), PresetLink.parse(link))
        listOf("location", "owner", "title", "date", "resolution", "1080")
            .forEach { assertFalse(link.contains(it, ignoreCase = true)) }
        assertTrue(link.matches(Regex("https://ahn-lab\\.org/google-timeline-visualizer/\\?preset=[A-Za-z0-9_-]{4,16}")))
    }

    @Test
    fun linkParserRejectsWrongOriginsDuplicatesAndOversizedPayloads() {
        val token = PresetCodec.encode(values)

        assertEquals(PresetDecodeResult.Invalid, PresetLink.parse("https://example.com/?preset=$token"))
        assertEquals(
            PresetDecodeResult.Invalid,
            PresetLink.parse("${PresetLink.HTTPS_BASE}?preset=$token&preset=$token"),
        )
        assertEquals(
            PresetDecodeResult.Invalid,
            PresetLink.parse("${PresetLink.HTTPS_BASE}?preset=${"a".repeat(100)}"),
        )
    }
}
