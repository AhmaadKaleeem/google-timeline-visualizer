import { selectDateRange, selectRange } from './timeline';
import type { GeoPoint } from './types';

export interface SemanticSelection {
  readonly exactDates: boolean;
  readonly startMonth: string;
  readonly endMonth: string;
  readonly startDate: string;
  readonly endDate: string;
}

/** Selects one source without changing the semantic range hidden while raw mode is active. */
export function selectTimelineModePoints(
  useRaw: boolean,
  rawPoints: GeoPoint[],
  semanticPoints: GeoPoint[],
  selection: SemanticSelection,
): GeoPoint[] {
  if (useRaw) return rawPoints;
  return selection.exactDates
    ? selectDateRange(semanticPoints, selection.startDate, selection.endDate)
    : selectRange(semanticPoints, selection.startMonth, selection.endMonth);
}
