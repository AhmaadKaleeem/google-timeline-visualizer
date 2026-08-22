package dev.mahlernim.timelinevisualizer.presets

import java.net.URI

object PresetLink {
    const val HTTPS_BASE = "https://ahn-lab.org/google-timeline-visualizer/"
    private const val HTTPS_HOST = "ahn-lab.org"
    private const val HTTPS_PATH = "/google-timeline-visualizer/"
    private const val CUSTOM_SCHEME = "timelinevisualizer"

    fun create(values: PresetValues): String = HTTPS_BASE + "?preset=" + PresetCodec.encode(values)

    fun isPresetLink(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return (
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(HTTPS_HOST, ignoreCase = true) && uri.path == HTTPS_PATH &&
                uri.rawQuery?.split('&')?.any { it.substringBefore('=') == "preset" } == true
            ) || (
            uri.scheme.equals(CUSTOM_SCHEME, ignoreCase = true) &&
                uri.host.equals("preset", ignoreCase = true)
            )
    }

    fun parse(raw: String): PresetDecodeResult {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return PresetDecodeResult.Invalid
        val token = when {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(HTTPS_HOST, ignoreCase = true) && uri.path == HTTPS_PATH -> {
                val pairs = uri.rawQuery?.split('&').orEmpty().mapNotNull { pair ->
                    val parts = pair.split('=', limit = 2)
                    if (parts.firstOrNull() == "preset" && parts.size == 2) parts[1] else null
                }
                pairs.singleOrNull()
            }
            uri.scheme.equals(CUSTOM_SCHEME, ignoreCase = true) &&
                uri.host.equals("preset", ignoreCase = true) -> uri.path?.removePrefix("/")
            else -> null
        } ?: return PresetDecodeResult.Invalid
        return PresetCodec.decode(token)
    }
}
