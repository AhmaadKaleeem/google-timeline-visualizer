import { describe, expect, it } from 'vitest';
import {
  automaticDistanceUnit,
  convertDistanceFromKilometers,
  isDistanceUnitPreference,
  resolveDistanceUnit,
} from './distance-unit';

describe('distance units', () => {
  it.each(['en-US', 'en-GB', 'my-MM', 'en-LR'])('uses miles for %s', (locale) => {
    expect(automaticDistanceUnit([locale])).toBe('miles');
  });

  it.each(['ko-KR', 'ja-JP', 'de-DE', 'fr-CA'])('uses kilometers for %s', (locale) => {
    expect(automaticDistanceUnit([locale])).toBe('kilometers');
  });

  it('maximizes a language-only locale and skips invalid locale tags', () => {
    expect(automaticDistanceUnit(['not_a_locale', 'en'])).toBe('miles');
    expect(automaticDistanceUnit(['not_a_locale'])).toBe('kilometers');
  });

  it('keeps explicit preferences independent of the browser locale', () => {
    expect(resolveDistanceUnit('kilometers', ['en-US'])).toBe('kilometers');
    expect(resolveDistanceUnit('miles', ['ko-KR'])).toBe('miles');
  });

  it('recognizes only supported stored preferences', () => {
    expect(isDistanceUnitPreference('automatic')).toBe(true);
    expect(isDistanceUnitPreference('kilometers')).toBe(true);
    expect(isDistanceUnitPreference('miles')).toBe(true);
    expect(isDistanceUnitPreference('mi')).toBe(false);
  });

  it('converts display values without changing the kilometer source value', () => {
    expect(convertDistanceFromKilometers(10, 'kilometers')).toBe(10);
    expect(convertDistanceFromKilometers(10, 'miles')).toBeCloseTo(6.21371192237334, 12);
  });
});
