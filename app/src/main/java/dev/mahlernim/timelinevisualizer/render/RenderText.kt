package dev.mahlernim.timelinevisualizer.render

import java.time.format.DateTimeFormatter
import java.util.Locale

data class RenderText(
    val localeTag: String,
    val fallbackTitle: String,
    val datePattern: String,
    val distanceUnit: String,
    val attribution: String,
) {
    val locale: Locale get() = Locale.forLanguageTag(localeTag).takeUnless { it.language.isBlank() } ?: Locale.ENGLISH

    // A translated pattern may hold reserved or unknown letters (a translator turning "yyyy"
    // into "aaaa" or "jjjj"), and ofPattern throws for those while the first frame renders on
    // the main thread. Fall back to the default pattern instead of crashing playback.
    val dateFormatter: DateTimeFormatter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { DateTimeFormatter.ofPattern(datePattern, locale) }
            .getOrElse { DateTimeFormatter.ofPattern(DEFAULT_DATE_PATTERN, locale) }
    }

    companion object {
        const val DEFAULT_DATE_PATTERN = "MMMM yyyy"
        val ENGLISH = RenderText(
            localeTag = "en",
            fallbackTitle = "My Timeline",
            datePattern = "MMMM yyyy",
            distanceUnit = "km",
            attribution = "© OpenStreetMap  © CARTO",
        )
    }
}
