import type { Color } from './types';

/**
 * Board geometry on the classic 15x15 grid. A cell is [col, row]; row 0 is the
 * far edge. Yards: red top-left, green top-right, yellow bottom-right, blue
 * bottom-left. Play runs clockwise red -> green -> yellow -> blue.
 */
export type Cell = readonly [number, number];

export const GRID = 15;
export const TRACK_LEN = 52;
export const HOME_START = 51;
export const FINISH = 56;

export const START_OFFSET: Record<Color, number> = { red: 0, green: 13, yellow: 26, blue: 39 };

/** Absolute track indices that protect tokens (start cells and star cells). */
export const SAFE_INDICES: readonly number[] = [0, 8, 13, 21, 26, 34, 39, 47];

function buildTrack(): Cell[] {
  const t: Cell[] = [];
  for (let c = 1; c <= 5; c++) t.push([c, 6]);
  for (let r = 5; r >= 0; r--) t.push([6, r]);
  t.push([7, 0]);
  for (let r = 0; r <= 5; r++) t.push([8, r]);
  for (let c = 9; c <= 14; c++) t.push([c, 6]);
  t.push([14, 7]);
  for (let c = 14; c >= 9; c--) t.push([c, 8]);
  for (let r = 9; r <= 14; r++) t.push([8, r]);
  t.push([7, 14]);
  for (let r = 14; r >= 9; r--) t.push([6, r]);
  for (let c = 5; c >= 0; c--) t.push([c, 8]);
  t.push([0, 7]);
  t.push([0, 6]);
  return t;
}

export const TRACK: readonly Cell[] = buildTrack();

export const HOME_COLUMN: Record<Color, readonly Cell[]> = {
  red: [1, 2, 3, 4, 5].map((c) => [c, 7] as const),
  green: [1, 2, 3, 4, 5].map((r) => [7, r] as const),
  yellow: [13, 12, 11, 10, 9].map((c) => [c, 7] as const),
  blue: [13, 12, 11, 10, 9].map((r) => [7, r] as const),
};

/** Top-left cell of each 6x6 yard. */
export const YARD_ORIGIN: Record<Color, Cell> = {
  red: [0, 0],
  green: [9, 0],
  yellow: [9, 9],
  blue: [0, 9],
};

/** Yard well centres in (fractional) cell units, relative to the yard origin. */
export const YARD_SLOTS: readonly Cell[] = [
  [1.5, 1.5],
  [3.5, 1.5],
  [1.5, 3.5],
  [3.5, 3.5],
];

/** Where finished tokens rest, on the colour's triangle of the centre. */
export const FINISH_SPOT: Record<Color, Cell> = {
  red: [6.15, 7],
  green: [7, 6.15],
  yellow: [7.85, 7],
  blue: [7, 7.85],
};

export function absIndex(color: Color, progress: number): number | null {
  if (progress < 0 || progress >= HOME_START) return null;
  return (progress + START_OFFSET[color]) % TRACK_LEN;
}

export function isSafeIndex(index: number): boolean {
  return SAFE_INDICES.includes(index);
}

/** Grid cell (possibly fractional) of a token at a given progress. */
export function cellOf(color: Color, progress: number, token: number): Cell {
  if (progress < 0) {
    const o = YARD_ORIGIN[color];
    const s = YARD_SLOTS[token % 4];
    return [o[0] + s[0], o[1] + s[1]];
  }
  if (progress < HOME_START) return TRACK[absIndex(color, progress)!];
  if (progress < FINISH) return HOME_COLUMN[color][progress - HOME_START];
  return FINISH_SPOT[color];
}

/** Key that identifies a board location so co-located tokens can be grouped. */
export function locationKey(color: Color, progress: number, token: number): string {
  if (progress < 0) return `yard:${color}:${token}`;
  if (progress < HOME_START) return `t:${absIndex(color, progress)}`;
  if (progress < FINISH) return `h:${color}:${progress}`;
  return `f:${color}`;
}
