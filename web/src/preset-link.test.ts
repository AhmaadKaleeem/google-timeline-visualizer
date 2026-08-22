import { describe, expect, it } from 'vitest';
import { parsePresetToken, presetIntentUrl } from './preset-link';

describe('preset link fallback', () => {
  it('accepts one bounded URL-safe token', () => {
    expect(parsePresetToken('?preset=oQAA')).toBe('oQAA');
    expect(parsePresetToken('?source=test&preset=oQAA')).toBe('oQAA');
  });

  it('rejects missing, duplicate, oversized, and unsafe values', () => {
    expect(parsePresetToken('')).toBeNull();
    expect(parsePresetToken('?preset=oQAA&preset=oQAA')).toBeNull();
    expect(parsePresetToken(`?preset=${'a'.repeat(100)}`)).toBeNull();
    expect(parsePresetToken('?preset=bad%2Bvalue')).toBeNull();
  });

  it('builds an Android intent with an encoded safe fallback', () => {
    const result = presetIntentUrl('oQAA', 'https://ahn-lab.org/google-timeline-visualizer/?preset=oQAA');
    expect(result).toContain('intent://preset/oQAA#Intent;scheme=timelinevisualizer;');
    expect(result).toContain('package=dev.mahlernim.timelinevisualizer;');
    expect(result).toContain('S.browser_fallback_url=https%3A%2F%2Fahn-lab.org');
  });
});
