import { describe, expect, it } from 'vitest';
import {
  availableMonths,
  localDateKey,
  parseCoordinate,
  parseTimelineJson,
  selectDateRange,
  selectRange,
  TimelineParseError,
} from './timeline';

describe('parseCoordinate', () => {
  it('supports iOS, geo, object, and E7 coordinate forms', () => {
    expect(parseCoordinate('37.5°, 127.0°')).toEqual([37.5, 127]);
    expect(parseCoordinate('geo:37.5,127?z=12')).toEqual([37.5, 127]);
    expect(parseCoordinate({ latLng: '37.5,127' })).toEqual([37.5, 127]);
    expect(parseCoordinate('375000000,1270000000')).toEqual([37.5, 127]);
  });

  it('rejects invalid coordinates', () => {
    expect(parseCoordinate('91,127')).toBeNull();
    expect(parseCoordinate('not a coordinate')).toBeNull();
  });
});

describe('parseTimelineJson', () => {
  const directExport = [
    {
      startTime: '2025-01-10T00:00:00Z',
      endTime: '2025-01-10T01:00:00Z',
      activity: { start: 'geo:37.5,127', end: 'geo:35.1,129' },
    },
    {
      startTime: '2025-03-10T00:00:00Z',
      timelinePath: [{ point: '33.5°, 126.5°', time: '2025-03-10T00:00:00Z' }],
    },
  ];

  it('supports direct-array and semanticSegments roots', () => {
    expect(parseTimelineJson(directExport)).toHaveLength(3);
    expect(parseTimelineJson({ semanticSegments: directExport })).toHaveLength(3);
  });

  it('parses string and numeric offsets from a segment start', () => {
    const points = parseTimelineJson([{
      startTime: '2026-01-01T00:00:00Z',
      endTime: '2026-01-01T02:00:00Z',
      timelinePath: [
        { point: '37.0,127.0', durationMinutesOffsetFromStartTime: '15' },
        { point: '37.1,127.1', durationMinutesOffsetFromStartTime: 60 },
      ],
    }]);
    expect(points.map((point) => point.instant.toISOString())).toEqual([
      '2026-01-01T00:15:00.000Z',
      '2026-01-01T01:00:00.000Z',
    ]);
  });

  it('prefers an absolute path time and ignores invalid offsets', () => {
    const points = parseTimelineJson([{
      startTime: '2026-01-01T00:00:00Z',
      endTime: '2026-01-01T01:00:00Z',
      timelinePath: [
        { point: '37.0,127.0', time: '2026-01-01T00:30:00Z', durationMinutesOffsetFromStartTime: '5' },
        { point: '37.1,127.1', durationMinutesOffsetFromStartTime: '-1' },
        { point: '37.2,127.2', durationMinutesOffsetFromStartTime: 'unknown' },
        { point: '37.3,127.3', durationMinutesOffsetFromStartTime: '120' },
      ],
      visit: { topCandidate: { placeLocation: '37.4,127.4' } },
    }]);
    expect(points).toHaveLength(2);
    expect(points[0].instant.toISOString()).toBe('2026-01-01T00:00:00.000Z');
    expect(points[1].instant.toISOString()).toBe('2026-01-01T00:30:00.000Z');
  });

  it('sorts, deduplicates, lists months, and selects a month range', () => {
    const duplicate = { ...directExport[0] };
    const points = parseTimelineJson([directExport[1], directExport[0], duplicate]);
    expect(points).toHaveLength(3);
    expect(points[0].instant.toISOString()).toBe('2025-01-10T00:00:00.000Z');
    expect(availableMonths(points).map((month) => month.key)).toEqual(['2025-01', '2025-03']);
    expect(selectRange(points, '2025-03', '2025-03')).toHaveLength(1);
  });

  it('rejects unsupported or empty exports', () => {
    expect(() => parseTimelineJson({ locations: [] })).toThrow(TimelineParseError);
    expect(() => parseTimelineJson([])).toThrow('no usable location points');
  });

  it('classifies unsupported export formats', () => {
    const reason = (value: unknown) => {
      try {
        parseTimelineJson(value);
        throw new Error('Expected parsing to fail');
      } catch (error) {
        expect(error).toBeInstanceOf(TimelineParseError);
        return (error as TimelineParseError).reason;
      }
    };
    expect(reason({ timelineObjects: [] })).toBe('legacy-format');
    expect(reason({ locations: [] })).toBe('legacy-format');
    expect(reason({ rawSignals: [] })).toBe('raw-signals-only');
    expect(reason({ semanticSegments: [] })).toBe('no-usable-locations');
  });

  it('selects an inclusive exact local-date range', () => {
    const points = parseTimelineJson(directExport);
    expect(localDateKey(points[0].instant)).toMatch(/^2025-01-10$/);
    expect(selectDateRange(points, '2025-03-10', '2025-03-10')).toHaveLength(1);
  });
});
