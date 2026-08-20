package dev.mahlernim.timelinevisualizer.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoFormatDeviceTest {
    @Test
    fun theDeviceCanEvaluateAndConfigureEveryReportedPreset() {
        val profiles = VideoEncoderSupport.deviceProfiles()
        assertTrue("The device exposed no H.264 encoder", profiles.isNotEmpty())

        VideoQuality.values().forEach { preset ->
            val support = VideoEncoderSupport.select(preset, profiles)
            Log.i(TAG, "${preset.name}: $support")
            if (support is EncoderSupport.Supported) configure(preset, support)
        }

        assertTrue(
            "The device cannot encode the default square format",
            VideoEncoderSupport.select(VideoQuality.STANDARD, profiles) is EncoderSupport.Supported,
        )
    }

    private fun configure(preset: VideoQuality, support: EncoderSupport.Supported) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            preset.width,
            preset.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, support.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, preset.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, preset.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(support.name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private companion object {
        const val TAG = "VideoFormatDeviceTest"
    }
}
