import type { GeoPoint, MonthOption } from './types';

export type TimelineParseReason =
  | 'malformed-json'
  | 'legacy-format'
  | 'raw-signals-only'
  | 'unsupported-format'
  | 'no-usable-locations';

export class TimelineParseError extends Error {
  constructor(
    public readonly reason: TimelineParseReason,
    message: string,
  ) {
    super(message);
    this.name = 'TimelineParseError';
  }
}

type JsonObject = Record<string, unknown>;

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function parseCoordinate(value: unknown): [number, number] | null {
  if (isObject(value)) {
    return parseCoordinate(value.latLng ?? value.point);
  }
  if (typeof value !== 'string' || value.trim() === '') return null;

  const cleaned = value
    .trim()
    .replace(/^geo:/, '')
    .split('?', 1)[0]
    .replaceAll('°', '')
    .replaceAll(' ', '');
  const parts = cleaned.split(',');
  if (parts.length < 2) return null;

  let latitude = Number(parts[0]);
  let longitude = Number(parts[1]);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  if (Math.abs(latitude) > 1_000_000 || Math.abs(longitude) > 1_000_000) {
    latitude /= 10_000_000;
    longitude /= 10_000_000;
  }
  if (latitude < -85.05112878 || latitude > 85.05112878 || longitude < -180 || longitude > 180) {
    return null;
  }
  return [latitude, longitude];
}

function parseInstant(value: unknown): Date | null {
  if (typeof value !== 'string' || value.trim() === '') return null;
  const instant = new Date(value);
  return Number.isNaN(instant.getTime()) ? null : instant;
}

function parseOffsetMinutes(value: unknown): number | null {
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) return null;
  return parsed;
}

function parseOffsetInstant(startValue: unknown, endValue: unknown, offsetValue: unknown): Date | null {
  const start = parseInstant(startValue);
  const offsetMinutes = parseOffsetMinutes(offsetValue);
  if (!start || offsetMinutes === null) return null;
  const timestamp = start.getTime() + offsetMinutes * 60_000;
  if (!Number.isSafeInteger(timestamp)) return null;
  const instant = new Date(timestamp);
  if (Number.isNaN(instant.getTime())) return null;
  const end = parseInstant(endValue);
  if (end && instant.getTime() > end.getTime() + 60_000) return null;
  return instant;
}

function addPoint(output: GeoPoint[], time: unknown, coordinate: unknown): void {
  const instant = parseInstant(time);
  const parsed = parseCoordinate(coordinate);
  if (!instant || !parsed) return;
  output.push({ instant, latitude: parsed[0], longitude: parsed[1] });
}

export function parseTimelineJson(data: unknown): GeoPoint[] {
  let segments: unknown[];
  if (Array.isArray(data)) {
    segments = data;
  } else if (isObject(data) && Array.isArray(data.semanticSegments)) {
    segments = data.semanticSegments;
  } else if (isObject(data) && ('timelineObjects' in data || 'locations' in data)) {
    throw new TimelineParseError(
      'legacy-format',
      'This is an older Google Takeout format. Export Timeline data from your phone instead.',
    );
  } else if (isObject(data) && 'rawSignals' in data) {
    throw new TimelineParseError(
      'raw-signals-only',
      'This export contains raw signals but no reconstructed Timeline journeys.',
    );
  } else {
    throw new TimelineParseError(
      'unsupported-format',
      'Timeline JSON must be an array or contain semanticSegments.',
    );
  }

  const points: GeoPoint[] = [];
  for (const rawSegment of segments) {
    if (!isObject(rawSegment)) continue;
    const startTime = rawSegment.startTime;
    const endTime = rawSegment.endTime;

    if (Array.isArray(rawSegment.timelinePath)) {
      for (const rawPathPoint of rawSegment.timelinePath) {
        if (!isObject(rawPathPoint)) continue;
        const instant = parseInstant(rawPathPoint.time)
          ?? parseOffsetInstant(startTime, endTime, rawPathPoint.durationMinutesOffsetFromStartTime);
        const coordinate = parseCoordinate(rawPathPoint.point);
        if (instant && coordinate) {
          points.push({ instant, latitude: coordinate[0], longitude: coordinate[1] });
        }
      }
    }

    if (isObject(rawSegment.activity)) {
      addPoint(points, startTime, rawSegment.activity.start);
      addPoint(points, endTime, rawSegment.activity.end);
    }

    if (isObject(rawSegment.visit) && isObject(rawSegment.visit.topCandidate)) {
      addPoint(points, startTime, rawSegment.visit.topCandidate.placeLocation);
    }
  }

  const unique = new Map<string, GeoPoint>();
  for (const point of points) {
    const key = `${point.instant.getTime()}:${point.latitude}:${point.longitude}`;
    unique.set(key, point);
  }
  const normalized = [...unique.values()].sort((a, b) => a.instant.getTime() - b.instant.getTime());
  if (normalized.length === 0) {
    throw new TimelineParseError(
      'no-usable-locations',
      'This Timeline export contains no usable location points.',
    );
  }
  return normalized;
}

export function monthKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

export function availableMonths(points: GeoPoint[]): MonthOption[] {
  const formatter = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' });
  const keys = [...new Set(points.map((point) => monthKey(point.instant)))].sort();
  return keys.map((key) => {
    const [year, month] = key.split('-').map(Number);
    return { key, label: formatter.format(new Date(year, month - 1, 1)) };
  });
}

export function selectRange(points: GeoPoint[], startMonth: string, endMonth: string): GeoPoint[] {
  return points.filter((point) => {
    const key = monthKey(point.instant);
    return key >= startMonth && key <= endMonth;
  });
}

export function localDateKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function selectDateRange(points: GeoPoint[], startDate: string, endDate: string): GeoPoint[] {
  return points.filter((point) => {
    const key = localDateKey(point.instant);
    return key >= startDate && key <= endDate;
  });
}
