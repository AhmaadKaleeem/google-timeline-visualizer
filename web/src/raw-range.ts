import type { I18n } from './i18n';
import { localDateKey } from './timeline';
import type { GeoPoint } from './types';

/** Formats the effective local date range after every raw-signal filter has run. */
export function formatRawDateRange(points: readonly GeoPoint[], i18n: I18n): string {
  if (points.length === 0) return i18n.t('rawRangeEmpty');

  let first = points[0];
  let last = points[0];
  for (const point of points.slice(1)) {
    if (point.instant.getTime() < first.instant.getTime()) first = point;
    if (point.instant.getTime() > last.instant.getTime()) last = point;
  }

  const start = i18n.formatMediumDate(first.instant);
  if (points.length === 1) return i18n.t('rawRangeOnePoint', { date: start });

  const count = i18n.formatNumber(points.length);
  if (localDateKey(first.instant) === localDateKey(last.instant)) {
    return i18n.t('rawRangeOneDay', { count, date: start });
  }
  return i18n.t('rawRangeMultipleDays', {
    count,
    start,
    end: i18n.formatMediumDate(last.instant),
  });
}
