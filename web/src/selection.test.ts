import { describe, expect, it } from 'vitest';
import { selectTimelineModePoints } from './selection';
import type { GeoPoint } from './types';

function point(date: string): GeoPoint {
  return { instant: new Date(`${date}T12:00:00Z`), latitude: 37, longitude: 127 };
}

describe('selectTimelineModePoints', () => {
  it('restores the same semantic month selection after raw mode', () => {
    const january = point('2026-01-10');
    const february = point('2026-02-10');
    const raw = [point('2026-03-10')];
    const selection = Object.freeze({
      exactDates: false,
      startMonth: '2026-01',
      endMonth: '2026-01',
      startDate: '2026-01-01',
      endDate: '2026-01-31',
    });

    expect(selectTimelineModePoints(false, raw, [january, february], selection)).toEqual([january]);
    expect(selectTimelineModePoints(true, raw, [january, february], selection)).toBe(raw);
    expect(selectTimelineModePoints(false, raw, [january, february], selection)).toEqual([january]);
    expect(selection).toEqual({
      exactDates: false,
      startMonth: '2026-01',
      endMonth: '2026-01',
      startDate: '2026-01-01',
      endDate: '2026-01-31',
    });
  });

  it('restores an exact semantic date selection and supports raw-only input', () => {
    const first = point('2026-01-10');
    const selected = point('2026-01-20');
    const raw = [point('2026-03-10')];
    const selection = {
      exactDates: true,
      startMonth: '2026-01',
      endMonth: '2026-01',
      startDate: '2026-01-15',
      endDate: '2026-01-25',
    };

    expect(selectTimelineModePoints(false, raw, [first, selected], selection)).toEqual([selected]);
    expect(selectTimelineModePoints(true, raw, [], selection)).toBe(raw);
  });
});
