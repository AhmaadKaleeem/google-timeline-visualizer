import { describe, expect, it } from 'vitest';
import { buildCameraTrack, cameraViewportAt, worldPositionAtProgress } from './camera';
import { cumulativeDistances, project, unwrapWorldPoints } from './geo';
import { requiredTiles } from './renderer';
import type { CameraMovement, GeoPoint } from './types';

function journey(points: Array<[number, number]>) {
  const geoPoints: GeoPoint[] = points.map(([latitude, longitude], index) => ({
    instant: new Date(index * 60_000),
    latitude,
    longitude,
  }));
  const cumulativeDistanceKm = cumulativeDistances(geoPoints);
  return {
    worldPoints: unwrapWorldPoints(geoPoints.map((point) => project(point.latitude, point.longitude))),
    cumulativeDistanceKm,
    totalDistanceKm: cumulativeDistanceKm.at(-1) ?? 0,
  };
}

function center(viewport: ReturnType<typeof cameraViewportAt>): [number, number] {
  return [(viewport.minX + viewport.maxX) / 2, (viewport.minY + viewport.maxY) / 2];
}

describe('camera track', () => {
  const koreanJourney = journey([
    [37.5665, 126.9780],
    [37.4563, 126.7052],
    [36.3504, 127.3845],
    [35.8714, 128.6014],
    [35.1796, 129.0756],
  ]);

  it.each<CameraMovement>(['fixed', 'steady', 'dynamic'])('%s follows the journey instead of freezing', (movement) => {
    const track = buildCameraTrack(koreanJourney, 480, movement);
    const [startX, startY] = center(cameraViewportAt(track, 0));
    const [endX, endY] = center(cameraViewportAt(track, 1));
    expect(Math.hypot(endX - startX, endY - startY)).toBeGreaterThan(0.001);
  });

  it('keeps the marker inside the stable central area', () => {
    const track = buildCameraTrack(koreanJourney, 480, 'dynamic');
    for (let sample = 0; sample <= 40; sample += 1) {
      const progress = sample / 40;
      const viewport = cameraViewportAt(track, progress);
      const marker = worldPositionAtProgress(koreanJourney, progress).point;
      const normalizedX = (marker.x - viewport.minX) / (viewport.maxX - viewport.minX);
      const normalizedY = (marker.y - viewport.minY) / (viewport.maxY - viewport.minY);
      expect(normalizedX).toBeGreaterThanOrEqual(0.299);
      expect(normalizedX).toBeLessThanOrEqual(0.701);
      expect(normalizedY).toBeGreaterThanOrEqual(0.299);
      expect(normalizedY).toBeLessThanOrEqual(0.701);
    }
  });

  it('keeps one zoom span in fixed mode while continuing to pan', () => {
    const track = buildCameraTrack(koreanJourney, 480, 'fixed');
    const spans = [0, 0.2, 0.5, 0.8, 1].map((progress) => {
      const viewport = cameraViewportAt(track, progress);
      return viewport.maxY - viewport.minY;
    });
    spans.forEach((span) => expect(span).toBeCloseTo(spans[0], 12));
  });

  it('uses the short camera path and wrapped tiles across the date line', () => {
    const dateLineJourney = journey([[10, 179], [10.2, -179]]);
    const track = buildCameraTrack(dateLineJourney, 480, 'dynamic');
    const middle = cameraViewportAt(track, 0.5);
    expect(middle.maxX - middle.minX).toBeLessThan(0.05);
    const count = 2 ** middle.zoom;
    requiredTiles(middle).forEach((tile) => {
      expect(tile.x).toBeGreaterThanOrEqual(0);
      expect(tile.x).toBeLessThan(count);
    });
  });

  it('smooths changing spans and stabilizes integer tile zoom', () => {
    const changingJourney = journey([
      [37.5665, 126.9780],
      [37.5650, 126.9850],
      [35.1796, 129.0756],
      [35.1800, 129.0800],
    ]);
    const track = buildCameraTrack(changingJourney, 480, 'dynamic');
    for (let index = 1; index < track.frames.length; index += 1) {
      const previous = track.frames[index - 1];
      const current = track.frames[index];
      expect(Number.isFinite(current.spanY)).toBe(true);
      expect(Math.abs(Math.log(current.spanY / previous.spanY))).toBeLessThan(0.8);
      expect(Math.abs(current.zoom - previous.zoom)).toBeLessThanOrEqual(3);
    }
  });
});
