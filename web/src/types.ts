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

export type CameraMovement = 'fixed' | 'steady' | 'dynamic';

export interface CameraFrame {
  centerX: number;
  centerY: number;
  spanY: number;
  zoom: number;
}

export interface CameraTrack {
  frames: CameraFrame[];
  aspect: number;
}

export interface PreparedJourney {
  points: GeoPoint[];
  worldPoints: WorldPoint[];
  cumulativeDistanceKm: number[];
  totalDistanceKm: number;
  cameraTrack: CameraTrack;
  tiles: Map<string, HTMLImageElement>;
}
