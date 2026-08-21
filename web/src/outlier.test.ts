import { describe, expect, it } from 'vitest';
import { filterLocationOutliers } from './outlier';
import type { GeoPoint } from './types';

function point(instant: string, latitude: number, longitude: number): GeoPoint {
  return { instant: new Date(instant), latitude, longitude };
}

describe('filterLocationOutliers', () => {
  it('removes a single impossible out-and-back point', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T01:00:00Z', 0, -50),
      point('2026-01-01T02:00:00Z', 37.57, 126.98),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual([points[0], points[2]]);
    expect(result.removedCount).toBe(1);
  });

  it('removes a short clustered spoofing excursion', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T01:00:00Z', 0, -50),
      point('2026-01-01T01:10:00Z', 0.2, -50.1),
      point('2026-01-01T02:00:00Z', 37.57, 126.98),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual([points[0], points[3]]);
    expect(result.removedCount).toBe(2);
  });

  it('preserves plausibly timed intercontinental travel', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-02T14:00:00Z', -23.5505, -46.6333),
      point('2026-01-10T12:00:00Z', 37.57, 126.98),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('preserves one-way long-distance travel and endpoints', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T12:00:00Z', 35.6762, 139.6503),
      point('2026-01-02T12:00:00Z', 51.5072, -0.1276),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.points[0]).toBe(points[0]);
    expect(result.points.at(-1)).toBe(points[2]);
    expect(result.removedCount).toBe(0);
  });

  it('preserves a one way long distance hop inside the 12 hour window', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T01:00:00Z', 21.3069, -157.8583),
      point('2026-01-01T02:00:00Z', -33.8688, 151.2093),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('filters an excursion that rejoins within 200 km but keeps one that lands farther away', () => {
    const rejoining = [
      point('2026-01-01T00:00:00Z', 0, 0),
      point('2026-01-01T00:30:00Z', 0, -50),
      point('2026-01-01T01:00:00Z', 0, 1.3),
    ];
    const departing = [
      point('2026-01-01T00:00:00Z', 0, 0),
      point('2026-01-01T00:30:00Z', 0, -50),
      point('2026-01-01T01:00:00Z', 0, 2.5),
    ];

    expect(filterLocationOutliers(rejoining).removedCount).toBe(1);
    expect(filterLocationOutliers(rejoining).points).toEqual([rejoining[0], rejoining[2]]);
    expect(filterLocationOutliers(departing).removedCount).toBe(0);
    expect(filterLocationOutliers(departing).points).toEqual(departing);
  });

  it('returns every point in off mode', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T01:00:00Z', 0, -50),
      point('2026-01-01T02:00:00Z', 37.57, 126.98),
    ];

    const result = filterLocationOutliers(points, 'off');

    expect(result.points).toBe(points);
    expect(result.removedCount).toBe(0);
  });

  it('returns the input array unchanged for fewer than three points', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:30:00Z', 0, -50),
    ];

    const single = [points[0]];
    const empty: GeoPoint[] = [];

    expect(filterLocationOutliers(points).points).toBe(points);
    expect(filterLocationOutliers(points).removedCount).toBe(0);
    expect(filterLocationOutliers(single).points).toBe(single);
    expect(filterLocationOutliers(empty).points).toBe(empty);
    expect(filterLocationOutliers(empty).removedCount).toBe(0);
  });

  it('keeps a three point excursion whose hop is under 500 km', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:01:00Z', 37.5665, 132),
      point('2026-01-01T00:02:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('removes a run of exactly three outliers', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:10:00Z', 0, -50),
      point('2026-01-01T00:20:00Z', 0.2, -50.1),
      point('2026-01-01T00:30:00Z', 0.1, -50.05),
      point('2026-01-01T00:40:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual([points[0], points[4]]);
    expect(result.removedCount).toBe(3);
  });

  it('keeps a run of four outliers because it exceeds the run cap', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:10:00Z', 0, -50),
      point('2026-01-01T00:20:00Z', 0.2, -50.1),
      point('2026-01-01T00:30:00Z', 0.1, -50.05),
      point('2026-01-01T00:40:00Z', 0.05, -50.02),
      point('2026-01-01T00:50:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('keeps a cluster whose span exceeds 200 km', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:20:00Z', 0, -50),
      point('2026-01-01T00:40:00Z', 0, -53),
      point('2026-01-01T01:00:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('always keeps the last point', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:30:00Z', 37.5665, 126.978),
      point('2026-01-01T01:00:00Z', 0, -50),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('always keeps the first point', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 0, -50),
      point('2026-01-01T00:30:00Z', 37.5665, 126.978),
      point('2026-01-01T01:00:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('filters a window of exactly 12 hours but not one millisecond more', () => {
    const inside = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:30:00Z', 0, -50),
      point('2026-01-01T12:00:00Z', 37.5665, 126.978),
    ];
    const outside = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:30:00Z', 0, -50),
      point('2026-01-01T12:00:00.001Z', 37.5665, 126.978),
    ];

    expect(filterLocationOutliers(inside).removedCount).toBe(1);
    expect(filterLocationOutliers(outside).removedCount).toBe(0);
    expect(filterLocationOutliers(outside).points).toEqual(outside);
  });

  it('treats a zero length hop duration as infinite speed', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:00:00Z', 0, -50),
      point('2026-01-01T01:00:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual([points[0], points[2]]);
    expect(result.removedCount).toBe(1);
  });

  it('keeps everything when timestamps run backwards', () => {
    const points = [
      point('2026-01-01T02:00:00Z', 37.5665, 126.978),
      point('2026-01-01T01:00:00Z', 0, -50),
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual(points);
    expect(result.removedCount).toBe(0);
  });

  it('keeps an excursion below 1300 km per hour but removes one above it', () => {
    // 600.5 km in 30 minutes is 1200.9 km/h, which stays inside the plausible limit.
    const plausible = [
      point('2026-01-01T00:00:00Z', 0, 0),
      point('2026-01-01T00:30:00Z', 0, 5.4),
      point('2026-01-01T01:00:00Z', 0, 0),
    ];
    // 700.0 km in 30 minutes is 1400.0 km/h, which exceeds it.
    const implausible = [
      point('2026-01-01T00:00:00Z', 0, 0),
      point('2026-01-01T00:30:00Z', 0, 6.2952),
      point('2026-01-01T01:00:00Z', 0, -0.5),
    ];

    expect(filterLocationOutliers(plausible).points).toEqual(plausible);
    expect(filterLocationOutliers(plausible).removedCount).toBe(0);
    expect(filterLocationOutliers(implausible).points)
      .toEqual([implausible[0], implausible[2]]);
    expect(filterLocationOutliers(implausible).removedCount).toBe(1);
  });

  it('removes an excursion whose hop is just above 500 km', () => {
    // 550.4 km out and 594.9 km back, both far faster than any aircraft.
    const points = [
      point('2026-01-01T00:00:00Z', 0, 0),
      point('2026-01-01T00:10:00Z', 0, 4.95),
      point('2026-01-01T00:20:00Z', 0, -0.4),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual([points[0], points[2]]);
    expect(result.removedCount).toBe(1);
  });

  it('compares each candidate against the last kept point', () => {
    const points = [
      point('2026-01-01T00:00:00Z', 37.5665, 126.978),
      point('2026-01-01T00:30:00Z', 0, -50),
      point('2026-01-01T01:00:00Z', 37.5665, 126.978),
      point('2026-01-01T01:30:00Z', 0, -50),
      point('2026-01-01T02:00:00Z', 37.5665, 126.978),
    ];

    const result = filterLocationOutliers(points);

    expect(result.points).toEqual([points[0], points[2], points[4]]);
    expect(result.removedCount).toBe(2);
  });
});
