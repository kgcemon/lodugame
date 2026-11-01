import type { Group } from 'three';
import type { Color, GameState } from '../../shared/types';
import { computeTargets, type TokenTarget } from '../three/layout';

/**
 * Mutable, non-React state shared between the animation director and the 3D
 * components. Reading these in useFrame avoids re-rendering React every frame.
 */

export interface TokenRuntime {
  group: Group | null;
  /** While true the director drives the transform; otherwise the token eases to its target. */
  scripted: boolean;
  /** Landing squash amount, decays to 0. */
  squash: number;
  /** Extra spin applied during capture flights. */
  spin: number;
}

export const tokens = new Map<string, TokenRuntime>();
export let targets = new Map<string, TokenTarget>();

export function tokenRuntime(key: string): TokenRuntime {
  let rt = tokens.get(key);
  if (!rt) {
    rt = { group: null, scripted: false, squash: 0, spin: 0 };
    tokens.set(key, rt);
  }
  return rt;
}

export function syncTargets(state: GameState | null): void {
  targets = state ? computeTargets(state) : new Map();
}

export interface DiceApi {
  roll: (value: number, color: Color) => Promise<void>;
  moveTo: (color: Color) => Promise<void>;
  place: (color: Color, value: number | null) => void;
}

export const dice: { api: DiceApi | null } = { api: null };

export interface EffectsApi {
  burst: (x: number, y: number, z: number, color: string, count?: number, power?: number) => void;
  ring: (x: number, y: number, z: number, color: string) => void;
}

export const effects: { api: EffectsApi | null } = { api: null };

/** Where the camera should gently lean toward (null = board centre). */
export const cameraFocus: { point: [number, number, number] | null; celebrate: boolean } = {
  point: null,
  celebrate: false,
};
