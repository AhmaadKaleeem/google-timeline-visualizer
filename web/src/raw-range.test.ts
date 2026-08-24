import { describe, expect, it } from 'vitest';
import { createI18n } from './i18n';
import { formatRawDateRange } from './raw-range';
import { parseRawSignalsJson, processRawSignals } from './timeline';
import type { GeoPoint } from './types';

const en = createI18n('en', 'en-US');

function point(year: number, month: number, day: number, hour = 12): GeoPoint {
  return {
    instant: new Date(year, month - 1, day, hour),
    latitude: 37,
    longitude: 127,
  };
}

describe('formatRawDateRange', () => {
  it('has an explicit empty state', () => {
    expect(formatRawDateRange([], en))
      .toBe('No raw location estimates remain with this accuracy limit.');
  });

  it('distinguishes one point from several points on one day', () => {
    const date = en.formatMediumDate(point(2026, 2, 3).instant);
    expect(formatRawDateRange([point(2026, 2, 3)], en))
      .toBe(`1 raw location estimate on ${date}`);
    expect(formatRawDateRange([point(2026, 2, 3, 8), point(2026, 2, 3, 18)], en))
      .toBe(`2 raw location estimates on ${date}`);
  });

  it('uses the earliest and latest local dates even when input order differs', () => {
    const first = point(2026, 2, 1);
    const last = point(2026, 2, 5);
    expect(formatRawDateRange([last, point(2026, 2, 3), first], en)).toBe(
      `3 raw location estimates from ${en.formatMediumDate(first.instant)} to ${en.formatMediumDate(last.instant)}`,
    );
  });

  it('updates the effective endpoint after the accuracy filter removes it', () => {
    const raw = parseRawSignalsJson({
      rawSignals: [
        { position: { LatLng: '37,127', timestamp: '2026-02-01T12:00:00Z', accuracyMeters: 200 } },
        { position: { LatLng: '37.1,127.1', timestamp: '2026-02-02T12:00:00Z', accuracyMeters: 20 } },
        { position: { LatLng: '37.2,127.2', timestamp: '2026-02-03T12:00:00Z', accuracyMeters: 20 } },
      ],
    });
    const unfiltered = processRawSignals(raw, null).points;
    const filtered = processRawSignals(raw, 100).points;

    expect(formatRawDateRange(unfiltered, en)).toContain(
      en.formatMediumDate(raw[0].instant),
    );
    expect(formatRawDateRange(filtered, en)).not.toContain(
      en.formatMediumDate(raw[0].instant),
    );
    expect(formatRawDateRange(filtered, en)).toContain(
      en.formatMediumDate(raw[1].instant),
    );
  });

  it('presents raw positions identically in mixed and raw-only exports', () => {
    const rawSignals = [
      { position: { LatLng: '37,127', timestamp: '2026-02-01T12:00:00Z', accuracyMeters: 20 } },
      { position: { LatLng: '37.2,127.2', timestamp: '2026-02-03T12:00:00Z', accuracyMeters: 20 } },
    ];
    const rawOnly = processRawSignals(parseRawSignalsJson({ rawSignals }), 100).points;
    const mixed = processRawSignals(parseRawSignalsJson({ rawSignals, semanticSegments: [] }), 100).points;
    expect(formatRawDateRange(mixed, en)).toBe(formatRawDateRange(rawOnly, en));
  });
});
