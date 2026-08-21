import { describe, expect, it } from 'vitest';
import {
  blendViewport,
  buildCameraTrack,
  cameraViewportAt,
  overviewSafeArea,
  overviewViewport,
  worldPositionAtProgress,
} from './camera';
import { cumulativeDistances, project, unwrapJourneyPoints, unwrapWorldPoints } from './geo';
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
    worldPoints: unwrapJourneyPoints(geoPoints),
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

  it('moves back west on the return leg of an Arctic round trip', () => {
    const arcticRoundTrip = journey([
      [37, 127],
      [70, 170],
      [82, -170],
      [38, -122],
      [70, -60],
      [82, 20],
      [70, 100],
      [37, 127],
    ]);
    const track = buildCameraTrack(arcticRoundTrip, 480, 'dynamic');
    const turnProgress = arcticRoundTrip.cumulativeDistanceKm[3] / arcticRoundTrip.totalDistanceKm;
    const [startX] = center(cameraViewportAt(track, 0));
    const [turnX] = center(cameraViewportAt(track, turnProgress));
    const [endX] = center(cameraViewportAt(track, 1));

    expect(turnX).toBeGreaterThan(startX);
    expect(endX).toBeLessThan(turnX);
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

  it('fits the complete route below the Android-style video header', () => {
    const size = 480;
    const viewport = overviewViewport(koreanJourney, size);
    const safe = overviewSafeArea(size);
    koreanJourney.worldPoints.forEach((point) => {
      const screenX = (point.x - viewport.minX) / (viewport.maxX - viewport.minX) * size;
      const screenY = (point.y - viewport.minY) / (viewport.maxY - viewport.minY) * size;
      expect(screenX).toBeGreaterThanOrEqual(safe.left);
      expect(screenX).toBeLessThanOrEqual(safe.right);
      expect(screenY).toBeGreaterThanOrEqual(safe.top);
      expect(screenY).toBeLessThanOrEqual(safe.bottom);
    });
  });

  it('calculates the same overview above browser argument limits', () => {
    const endpoints = unwrapWorldPoints([project(70, 20), project(-55, 20)]);
    const denseJourney = {
      worldPoints: Array.from({ length: 200_000 }, (_, index) => endpoints[index % endpoints.length]),
      cumulativeDistanceKm: [],
      totalDistanceKm: 0,
    };
    const endpointJourney = {
      worldPoints: endpoints,
      cumulativeDistanceKm: [],
      totalDistanceKm: 0,
    };

    expect(overviewViewport(denseJourney, 480)).toEqual(overviewViewport(endpointJourney, 480));
  });

  it('builds the moving camera above browser argument limits', () => {
    const point = koreanJourney.worldPoints[0];
    const pointCount = 130_000;
    const denseJourney = {
      worldPoints: Array.from({ length: pointCount }, () => point),
      cumulativeDistanceKm: new Array<number>(pointCount).fill(0),
      totalDistanceKm: 0,
    };

    const track = buildCameraTrack(denseJourney, 480, 'steady');

    expect(track.frames).toHaveLength(481);
    expect(track.frames.every((frame) => Number.isFinite(frame.spanY))).toBe(true);
  });

  it('blends from the final following view to the full-route ending view', () => {
    const track = buildCameraTrack(koreanJourney, 480, 'dynamic');
    const following = cameraViewportAt(track, 1);
    const overview = overviewViewport(koreanJourney, 480);
    expect(blendViewport(following, overview, 0, 480)).toEqual(following);
    const ending = blendViewport(following, overview, 1, 480);
    expect(ending.minX).toBeCloseTo(overview.minX, 12);
    expect(ending.maxX).toBeCloseTo(overview.maxX, 12);
    expect(ending.minY).toBeCloseTo(overview.minY, 12);
    expect(ending.maxY).toBeCloseTo(overview.maxY, 12);
  });
});
