package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant

/** Replaces one prepared time window without reconstructing the lifetime Journal route. */
fun JournalRoute.replacingWindow(
    start: Instant,
    endExclusive: Instant,
    replacement: JournalRoute,
): JournalRoute {
    require(endExclusive > start) { "The replacement window must not be empty" }
    val before = mutableListOf<RouteSpan>()
    val after = mutableListOf<RouteSpan>()
    spans.forEach { span ->
        if (span.end < start || (span.source == RouteSource.GAP && span.end == start)) {
            before += span
        } else if (span.start >= endExclusive) {
            after += span
        } else if (span.source == RouteSource.GAP) {
            if (span.start < start) before += span.copy(end = start)
            if (span.end >= endExclusive) after += span.copy(start = endExclusive)
        } else {
            span.points.filter { it.instant < start }.toSpanOrNull(span)?.let(before::add)
            span.points.filter { it.instant >= endExclusive }.toSpanOrNull(span)?.let(after::add)
        }
    }
    val mergedSpans = (before + replacement.spans + after)
        .sortedWith(compareBy<RouteSpan> { it.start }.thenBy { it.end })
    val flattened = mergedSpans.asSequence()
        .filter { it.source != RouteSource.GAP }
        .flatMap { it.points.asSequence() }
        .distinctBy(::routePointKey)
        .sortedBy(GeoPoint::instant)
        .toList()
    return copy(
        timeline = Timeline(flattened),
        spans = mergedSpans,
        // These counters are diagnostic only. A bounded replacement cannot derive lifetime totals
        // without repeating the expensive lifetime query that this path intentionally avoids.
        detailedInputCount = detailedInputCount,
        detailedUsableCount = detailedUsableCount,
        semanticUsableCount = semanticUsableCount,
    )
}

/** Expands a changed interval to complete existing components so fusion owns both cut boundaries. */
fun JournalRoute.expandedRefreshWindow(
    start: Instant,
    endExclusive: Instant,
): Pair<Instant, Instant> {
    require(endExclusive > start) { "The refresh window must not be empty" }
    if (spans.isEmpty()) return start to endExclusive
    val ordered = spans.sortedWith(compareBy<RouteSpan> { it.start }.thenBy { it.end })
    var first = ordered.indexOfFirst { it.end > start && it.start < endExclusive }
    var last = ordered.indexOfLast { it.end > start && it.start < endExclusive }
    if (first < 0) {
        val nearestBefore = ordered.indexOfLast { it.source != RouteSource.GAP && it.end <= start }
        val nearestAfter = ordered.indexOfFirst { it.source != RouteSource.GAP && it.start >= endExclusive }
        first = when {
            nearestBefore >= 0 -> nearestBefore
            nearestAfter >= 0 -> nearestAfter
            else -> return start to endExclusive
        }
        last = when {
            nearestBefore >= 0 && nearestAfter >= 0 -> nearestAfter
            else -> first
        }
    }

    // A gap affected by new observations needs both neighboring components present so the
    // replacement fusion can decide whether the gap remains. Otherwise stop at existing gaps.
    while (first > 0 && ordered[first - 1].source != RouteSource.GAP) first -= 1
    while (last < ordered.lastIndex && ordered[last + 1].source != RouteSource.GAP) last += 1

    val expandedStart = minOf(start, ordered[first].start)
    val lastEndMillis = ordered[last].end.toEpochMilli()
    val expandedEnd = Instant.ofEpochMilli(
        if (lastEndMillis == Long.MAX_VALUE) lastEndMillis else lastEndMillis + 1,
    )
    return expandedStart to maxOf(endExclusive, expandedEnd)
}

private fun List<GeoPoint>.toSpanOrNull(source: RouteSpan): RouteSpan? =
    takeIf { it.isNotEmpty() }?.let { points ->
        source.copy(
            start = points.first().instant,
            end = points.last().instant,
            points = points,
        )
    }

private fun routePointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
    point.instant.toEpochMilli(),
    point.latitude.toBits(),
    point.longitude.toBits(),
)
