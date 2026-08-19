import { describe, expect, it } from 'vitest';
import { isMp4 } from './video';

describe('isMp4', () => {
  it('accepts an ISO base media file signature', () => {
    const bytes = new Uint8Array([0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109]);
    expect(isMp4(bytes.buffer)).toBe(true);
  });

  it('rejects short and unrelated output', () => {
    expect(isMp4(new ArrayBuffer(4))).toBe(false);
    expect(isMp4(new TextEncoder().encode('not-an-mp4-file').buffer)).toBe(false);
  });
});
