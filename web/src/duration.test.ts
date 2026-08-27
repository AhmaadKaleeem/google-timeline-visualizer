import { describe, expect, it } from 'vitest';
import { countLargeTransfers, recommendedDurationSeconds } from './duration';

describe('duration recommendations', () => {
  it('uses actual viewport travel and zoom work', () => {
    const frames = [
      { centerX: 0.1, centerY: 0.5, spanY: 0.1, zoom: 5 },
      { centerX: 0.6, centerY: 0.5, spanY: 0.1, zoom: 5 },
      { centerX: 0.6, centerY: 0.5, spanY: 0.025, zoom: 7 },
    ];
    expect(recommendedDurationSeconds({ frames, aspect: 1 })).toBe(10);
    expect(recommendedDurationSeconds({ frames, aspect: 1 }, 2)).toBe(15);
  });

  it('counts transfers and wraps movement across the dateline', () => {
    expect(countLargeTransfers([0, 20, 170, 200, 450])).toBe(2);
    const frames = [
      { centerX: 0.99, centerY: 0.5, spanY: 0.1, zoom: 5 },
      { centerX: 0.01, centerY: 0.5, spanY: 0.1, zoom: 5 },
    ];
    expect(recommendedDurationSeconds({ frames, aspect: 1 })).toBe(10);
  });

  it('recommends beyond the default for a jump-heavy route', () => {
    const frames = Array.from({ length: 26 }, (_, index) => ({
      centerX: index * 0.01,
      centerY: 0.5,
      spanY: 0.01,
      zoom: 8,
    }));
    expect(recommendedDurationSeconds({ frames, aspect: 1 }, 3)).toBe(35);
  });
});
