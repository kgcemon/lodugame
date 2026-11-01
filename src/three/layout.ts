import { FINISH, HOME_START, YARD_ORIGIN, cellOf, locationKey, type Cell } from '../../shared/board';
import type { Color, GameState } from '../../shared/types';

/** World-space heights of the board surfaces (see Board.tsx). */
export const TILE_TOP = 0.17;
export const YARD_TOP = 0.29;
export const CENTER_BASE = 0.12;
export const CENTER_APEX = 0.55;
export const DICE_HALF = 0.42;

export const PLAYER_HEX: Record<Color, string> = {
  red: '#e53945',
  green: '#22b46b',
  yellow: '#f6bf26',
  blue: '#2f7cf0',
};

export function cellToWorld(c: Cell): [number, number] {
  return [c[0] - 7, c[1] - 7];
}

export function surfaceY(progress: number, x: number, z: number): number {
  if (progress < 0) return YARD_TOP;
  if (progress < FINISH) return TILE_TOP;
  // Finished tokens rest on the sloped centre wedge.
  const d = Math.max(Math.abs(x), Math.abs(z));
  return CENTER_BASE + (1 - Math.min(d, 1.5) / 1.5) * (CENTER_APEX - CENTER_BASE);
}

export interface TokenTarget {
  x: number;
  y: number;
  z: number;
  scale: number;
}

export const tokenKey = (player: number, token: number) => `${player}:${token}`;

/** Single-token position for a progress value (no clustering) — used for hop waypoints. */
export function pointFor(color: Color, progress: number, token: number): [number, number, number] {
  const [x, z] = cellToWorld(cellOf(color, progress, token));
  return [x, surfaceY(progress, x, z), z];
}

const CLUSTER: Record<number, [number, number][]> = {
  1: [[0, 0]],
  2: [[-0.2, -0.05], [0.2, 0.05]],
  3: [[-0.2, -0.14], [0.2, -0.14], [0, 0.18]],
  4: [[-0.2, -0.2], [0.2, -0.2], [-0.2, 0.2], [0.2, 0.2]],
};

/** Where every token should rest, spreading out tokens that share a square. */
export function computeTargets(state: GameState): Map<string, TokenTarget> {
  const groups = new Map<string, { p: number; t: number }[]>();
  state.players.forEach((pl, p) =>
    pl.tokens.forEach((prog, t) => {
      const k = locationKey(pl.color, prog, t);
      const g = groups.get(k);
      if (g) g.push({ p, t });
      else groups.set(k, [{ p, t }]);
    }),
  );
  const out = new Map<string, TokenTarget>();
  for (const members of groups.values()) {
    const n = Math.min(members.length, 4);
    members.forEach((m, i) => {
      const pl = state.players[m.p];
      const prog = pl.tokens[m.t];
      const [cx, , cz] = pointFor(pl.color, prog, m.t);
      const finished = prog === FINISH;
      const offs = CLUSTER[n][i % 4];
      const spread = finished ? 0.7 : 1;
      const x = cx + offs[0] * spread;
      const z = cz + offs[1] * spread;
      out.set(tokenKey(m.p, m.t), {
        x,
        z,
        y: surfaceY(prog, x, z),
        scale: finished ? 0.62 : n > 1 ? 0.78 : prog >= HOME_START ? 0.95 : 1,
      });
    });
  }
  return out;
}

/** Centre of a colour's yard, where the dice rests during that player's turn. */
export function yardCenter(color: Color): [number, number] {
  const o = YARD_ORIGIN[color];
  return cellToWorld([o[0] + 2.5, o[1] + 2.5]);
}

/** Camera azimuth that puts a colour's yard at the viewer's bottom-left. */
export const VIEW_AZIMUTH: Record<Color, number> = {
  blue: 0,
  red: -Math.PI / 2,
  green: Math.PI,
  yellow: Math.PI / 2,
};

/** Screen corner for each colour's panel given the colour at bottom-left. */
export function screenCorner(color: Color, viewColor: Color): 'bl' | 'tl' | 'tr' | 'br' {
  const order: Color[] = ['red', 'green', 'yellow', 'blue'];
  const d = (order.indexOf(color) - order.indexOf(viewColor) + 4) % 4;
  return (['bl', 'tl', 'tr', 'br'] as const)[d];
}
