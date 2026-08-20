import { describe, expect, it } from 'vitest';
import {
  cumulativeDistances,
  overviewRouteSegments,
  project,
  unwrapWorldPoints,
  viewportFor,
} from './geo';

describe('geography helpers', () => {
  it('projects valid Web Mercator coordinates', () => {
    expect(project(0, 0)).toEqual({ x: 0.5, y: 0.5 });
  });

  it('uses the short path across the international date line', () => {
    const points = unwrapWorldPoints([project(0, 179), project(0, -179)]);
    expect(Math.abs(points[1].x - points[0].x)).toBeLessThan(0.01);
    expect(viewportFor(points, 480).maxX - viewportFor(points, 480).minX).toBeLessThan(0.02);
  });

  it('collapses accumulated world copies for the ending overview', () => {
    const continuous = [
      { x: 0.85, y: 0.45 },
      { x: 1.15, y: 0.46 },
      { x: 1.50, y: 0.47 },
      { x: 1.85, y: 0.45 },
      { x: 2.15, y: 0.46 },
    ];

    const segments = overviewRouteSegments(continuous);
    const points = segments.flat();
    const viewport = viewportFor(points, 480);

    expect(viewport.maxX - viewport.minX).toBeCloseTo(1.28);
    expect(points.every((point) => point.x >= 1.65 && point.x <= 2.65)).toBe(true);
  });

  it('splits overview strokes at the wrapped map edge', () => {
    const segments = overviewRouteSegments([
      { x: 0.99, y: 0.40 },
      { x: 0.01, y: 0.42 },
      { x: 0.50, y: 0.44 },
    ]);

    expect(segments).toHaveLength(2);
    expect(segments[0].at(-1)?.x).toBeCloseTo(1);
    expect(segments[1][0].x).toBeCloseTo(0);
    expect(segments.flat().every((point) => point.x >= 0 && point.x <= 1)).toBe(true);
  });

  it('calculates cumulative distance', () => {
    const points = [
      { instant: new Date(0), latitude: 37.5665, longitude: 126.978 },
      { instant: new Date(1), latitude: 35.1796, longitude: 129.0756 },
    ];
    const distances = cumulativeDistances(points);
    expect(distances[0]).toBe(0);
    expect(distances[1]).toBeGreaterThan(320);
    expect(distances[1]).toBeLessThan(340);
  });
});
