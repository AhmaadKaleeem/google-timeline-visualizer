export interface GeoPoint {
  instant: Date;
  latitude: number;
  longitude: number;
}

export interface MonthOption {
  key: string;
  label: string;
}

export interface WorldPoint {
  x: number;
  y: number;
}

export interface Viewport {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
  zoom: number;
}

export interface PreparedJourney {
  points: GeoPoint[];
  worldPoints: WorldPoint[];
  cumulativeDistanceKm: number[];
  totalDistanceKm: number;
  viewport: Viewport;
  background: HTMLCanvasElement;
}
