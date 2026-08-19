import type { TimelineFrame } from './types';

export const OUTRO_SECONDS = 1.5;
export const OUTRO_TRANSITION_SECONDS = 1;

function clamp(value: number, minimum = 0, maximum = 1): number {
  return Math.max(minimum, Math.min(maximum, value));
}

export function totalDurationSeconds(journeyDurationSeconds: number): number {
  return Math.max(1, journeyDurationSeconds) + OUTRO_SECONDS;
}

export function frameAtElapsedSeconds(
  elapsedSeconds: number,
  journeyDurationSeconds: number,
): TimelineFrame {
  const journeySeconds = Math.max(1, journeyDurationSeconds);
  if (elapsedSeconds <= journeySeconds) {
    return { journeyProgress: clamp(elapsedSeconds / journeySeconds), outroProgress: 0 };
  }
  return {
    journeyProgress: 1,
    outroProgress: clamp((elapsedSeconds - journeySeconds) / OUTRO_TRANSITION_SECONDS),
  };
}

export function frameAtOverallProgress(
  overallProgress: number,
  journeyDurationSeconds: number,
): TimelineFrame {
  return frameAtElapsedSeconds(
    clamp(overallProgress) * totalDurationSeconds(journeyDurationSeconds),
    journeyDurationSeconds,
  );
}

export function easeOutCubic(value: number): number {
  const inverse = 1 - clamp(value);
  return 1 - inverse * inverse * inverse;
}

export function easeInOutCubic(value: number): number {
  const amount = clamp(value);
  if (amount < 0.5) return 4 * amount * amount * amount;
  const inverse = -2 * amount + 2;
  return 1 - inverse * inverse * inverse / 2;
}
