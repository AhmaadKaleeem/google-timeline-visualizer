import { describe, expect, it } from 'vitest';
import { cumulativeDistances, project, unwrapWorldPoints, viewportFor } from './geo';

describe('geography helpers', () => {
  it('projects valid Web Mercator coordinates', () => {
    expect(project(0, 0)).toEqual({ x: 0.5, y: 0.5 });
  });

  it('uses the short path across the international date line', () => {
    const points = unwrapWorldPoints([project(0, 179), project(0, -179)]);
    expect(Math.abs(points[1].x - points[0].x)).toBeLessThan(0.01);
    expect(viewportFor(points, 480).maxX - viewportFor(points, 480).minX).toBeLessThan(0.02);
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
