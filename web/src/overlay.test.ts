import { describe, expect, it } from 'vitest';
import { overlayCard, overlayScale } from './overlay';
import type { RenderSize } from './types';

const FORMATS: RenderSize[] = [
  { width: 480, height: 480 },
  { width: 720, height: 720 },
  { width: 1080, height: 1080 },
  { width: 1080, height: 1920 },
  { width: 1920, height: 1080 },
];

const SQUARES: RenderSize[] = FORMATS.filter((size) => size.width === size.height);

describe('overlay geometry', () => {
  it('scales the overlay by the short edge of a 720 design grid', () => {
    expect(overlayScale({ width: 480, height: 480 })).toBeCloseTo(0.6666666666666666, 12);
    expect(overlayScale({ width: 720, height: 720 })).toBe(1);
    expect(overlayScale({ width: 1080, height: 1080 })).toBe(1.5);
    expect(overlayScale({ width: 1080, height: 1920 })).toBe(1.5);
    expect(overlayScale({ width: 1920, height: 1080 })).toBe(1.5);
  });

  it('caps and centers the card on a landscape canvas instead of stretching it', () => {
    const card = overlayCard({ width: 1920, height: 1080 });
    expect(card.left).toBeCloseTo(471, 10);
    expect(card.width).toBeCloseTo(978, 10);
    expect(card.right).toBeCloseTo(1449, 10);
    expect(card.top).toBeCloseTo(42, 10);
    expect(card.bottom).toBeCloseTo(198, 10);
    expect(card.centerX).toBeCloseTo(960, 10);
  });

  it('insets the card from the side on a portrait canvas', () => {
    const card = overlayCard({ width: 1080, height: 1920 });
    expect(card.left).toBeCloseTo(51, 10);
    expect(card.width).toBeCloseTo(978, 10);
    expect(card.top).toBeCloseTo(42, 10);
    expect(card.bottom).toBeCloseTo(198, 10);
  });

  it.each(SQUARES)('matches the legacy square card geometry at $width x $height', (size) => {
    const scale = overlayScale(size);
    const card = overlayCard(size);
    expect(card.left).toBeCloseTo(34 * scale, 10);
    expect(card.width).toBeCloseTo(size.width - 68 * scale, 10);
    expect(card.bottom - card.top).toBeCloseTo(104 * scale, 10);
    expect(card.centerX).toBeCloseTo(size.width / 2, 10);
  });

  it.each(FORMATS)('keeps the card inside the canvas at $width x $height', (size) => {
    const card = overlayCard(size);
    expect(card.left).toBeGreaterThanOrEqual(0);
    expect(card.right).toBeLessThanOrEqual(size.width);
    expect(card.centerX).toBeCloseTo(size.width / 2, 10);
  });
});
