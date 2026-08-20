import type { GeoPoint, Viewport, WorldPoint } from './types';

export function project(latitude: number, longitude: number): WorldPoint {
  const lat = Math.max(-85.05112878, Math.min(85.05112878, latitude));
  const sinLat = Math.sin((lat * Math.PI) / 180);
  return {
    x: (longitude + 180) / 360,
    y: Math.max(0, Math.min(1, 0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI))),
  };
}

export function unwrapWorldPoints(points: WorldPoint[]): WorldPoint[] {
  if (points.length === 0) return [];
  const output = [{ ...points[0] }];
  for (let index = 1; index < points.length; index += 1) {
    let x = points[index].x;
    const previousX = output[index - 1].x;
    while (x - previousX > 0.5) x -= 1;
    while (x - previousX < -0.5) x += 1;
    output.push({ x, y: points[index].y });
  }
  return output;
}

function wrapNear(value: number, reference: number): number {
  return value - Math.floor(value - reference + 0.5);
}

export function overviewRouteSegments(points: WorldPoint[]): WorldPoint[][] {
  if (points.length === 0) return [];
  const referenceX = points.at(-1)?.x ?? 0.5;
  const lowerEdge = referenceX - 0.5;
  const upperEdge = referenceX + 0.5;
  const wrapped = points.map((point) => ({ x: wrapNear(point.x, referenceX), y: point.y }));
  const segments: WorldPoint[][] = [[wrapped[0]]];
  for (let index = 1; index < wrapped.length; index += 1) {
    const previous = wrapped[index - 1];
    const current = wrapped[index];
    const active = segments.at(-1)!;
    const delta = current.x - previous.x;
    if (Math.abs(delta) <= 0.5) {
      active.push(current);
      continue;
    }

    const crossingRight = delta < -0.5;
    const adjustedCurrentX = current.x + (crossingRight ? 1 : -1);
    const exitX = crossingRight ? upperEdge : lowerEdge;
    const enterX = crossingRight ? lowerEdge : upperEdge;
    const fraction = (exitX - previous.x) / (adjustedCurrentX - previous.x);
    const crossingY = previous.y + (current.y - previous.y) * fraction;
    active.push({ x: exitX, y: crossingY });
    segments.push([{ x: enterX, y: crossingY }, current]);
  }
  return segments;
}

export function viewportFor(points: WorldPoint[], size: number): Viewport {
  const minPointX = Math.min(...points.map((point) => point.x));
  const maxPointX = Math.max(...points.map((point) => point.x));
  const minPointY = Math.min(...points.map((point) => point.y));
  const maxPointY = Math.max(...points.map((point) => point.y));
  const centerX = (minPointX + maxPointX) / 2;
  const centerY = (minPointY + maxPointY) / 2;
  const span = Math.max(maxPointX - minPointX, maxPointY - minPointY, 0.002) * 1.28;
  const zoom = Math.max(2, Math.min(15, Math.floor(Math.log2(size / (256 * span)))));
  return {
    minX: centerX - span / 2,
    maxX: centerX + span / 2,
    minY: Math.max(0, centerY - span / 2),
    maxY: Math.min(1, centerY + span / 2),
    zoom,
  };
}

export function haversineKm(a: GeoPoint, b: GeoPoint): number {
  const lat1 = (a.latitude * Math.PI) / 180;
  const lat2 = (b.latitude * Math.PI) / 180;
  const dLat = lat2 - lat1;
  const dLon = ((b.longitude - a.longitude) * Math.PI) / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 6371.0088 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
}

export function cumulativeDistances(points: GeoPoint[]): number[] {
  const distances = new Array<number>(points.length).fill(0);
  for (let index = 1; index < points.length; index += 1) {
    distances[index] = distances[index - 1] + haversineKm(points[index - 1], points[index]);
  }
  return distances;
}
