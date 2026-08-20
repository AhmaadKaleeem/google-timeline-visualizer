import { describe, expect, it } from 'vitest';
import {
  frameAtElapsedSeconds,
  frameAtOverallProgress,
  totalDurationSeconds,
} from './animation';

describe('timeline ending', () => {
  it('adds the Android ending duration after the selected journey duration', () => {
    expect(totalDurationSeconds(30)).toBe(31.5);
    expect(totalDurationSeconds(75)).toBe(76.5);
  });

  it('zooms to the overview for one second and holds it for the last half-second', () => {
    expect(frameAtElapsedSeconds(30, 30)).toEqual({ journeyProgress: 1, outroProgress: 0 });
    expect(frameAtElapsedSeconds(31, 30)).toEqual({ journeyProgress: 1, outroProgress: 1 });
    expect(frameAtElapsedSeconds(31.4, 30)).toEqual({ journeyProgress: 1, outroProgress: 1 });
  });

  it('maps overall export progress across the journey and ending', () => {
    expect(frameAtOverallProgress(1, 10)).toEqual({ journeyProgress: 1, outroProgress: 1 });
    expect(frameAtOverallProgress(0, 10)).toEqual({ journeyProgress: 0, outroProgress: 0 });
  });
});
