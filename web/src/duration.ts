import type { CameraTrack } from './types';

export const MIN_DURATION_SECONDS = 10;
export const MAX_DURATION_SECONDS = 300;
export const DEFAULT_DURATION_SECONDS = 30;
const MAX_RECOMMENDED_DURATION_SECONDS = 60;

export function recommendedDurationSeconds(track: CameraTrack, largeTransferCount = 0): number {
  if (track.frames.length < 2 || !Number.isFinite(track.aspect) || track.aspect <= 0) {
    return DEFAULT_DURATION_SECONDS;
  }
  let movementWork = 0;
  let zoomWork = 0;
  for (let index = 1; index < track.frames.length; index += 1) {
    const previous = track.frames[index - 1];
    const current = track.frames[index];
    const spanY = Math.max(1e-9, Math.sqrt(previous.spanY * current.spanY));
    const spanX = Math.max(1e-9, spanY * track.aspect);
    movementWork += Math.hypot(wrappedDelta(current.centerX - previous.centerX) / spanX, (current.centerY - previous.centerY) / spanY);
    zoomWork += Math.abs(Math.log2(current.spanY / previous.spanY));
  }
  const seconds = 1.5 + movementWork / 0.9 + zoomWork / 1.5 + Math.max(0, largeTransferCount) * 1.5;
  return Math.max(MIN_DURATION_SECONDS, Math.min(MAX_RECOMMENDED_DURATION_SECONDS, Math.ceil(seconds / 5) * 5));
}

function wrappedDelta(delta: number): number {
  if (delta > 0.5) return delta - 1;
  if (delta < -0.5) return delta + 1;
  return delta;
}

export function countLargeTransfers(cumulativeDistanceKm: readonly number[], thresholdKm = 120): number {
  let count = 0;
  for (let index = 1; index < cumulativeDistanceKm.length; index += 1) {
    if (cumulativeDistanceKm[index] - cumulativeDistanceKm[index - 1] >= thresholdKm) count += 1;
  }
  return count;
}
