import { create } from 'zustand';
import { createJSONStorage, persist, type StateStorage } from 'zustand/middleware';
import type { Color } from '../../shared/types';

export type Quality = 'auto' | 'low' | 'medium' | 'high';
export type CameraPreset = 'tabletop' | 'top' | 'close';

export interface MatchRecord {
  at: number;
  mode: 'ai' | 'local' | 'online';
  players: { name: string; color: Color; rank: number | null; you: boolean }[];
  won: boolean;
}

interface SettingsState {
  name: string;
  avatarHue: number;
  sound: boolean;
  soundVolume: number;
  music: boolean;
  musicVolume: number;
  quality: Quality;
  camera: CameraPreset;
  autoMove: boolean;
  followTurns: boolean;
  history: MatchRecord[];
  set: (patch: Partial<Omit<SettingsState, 'set' | 'addRecord' | 'clearHistory'>>) => void;
  addRecord: (r: MatchRecord) => void;
  clearHistory: () => void;
}

/** localStorage can throw (private mode, blocked storage) — fall back to memory. */
const memory = new Map<string, string>();
const safeStorage: StateStorage = {
  getItem: (k) => {
    try {
      return localStorage.getItem(k);
    } catch {
      return memory.get(k) ?? null;
    }
  },
  setItem: (k, v) => {
    try {
      localStorage.setItem(k, v);
    } catch {
      memory.set(k, v);
    }
  },
  removeItem: (k) => {
    try {
      localStorage.removeItem(k);
    } catch {
      memory.delete(k);
    }
  },
};

const isMobile = typeof navigator !== 'undefined' && /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent);

export const useSettings = create<SettingsState>()(
  persist(
    (set) => ({
      name: 'Player',
      avatarHue: 210,
      sound: true,
      soundVolume: 0.8,
      music: false,
      musicVolume: 0.45,
      quality: 'auto',
      camera: 'tabletop',
      autoMove: true,
      followTurns: true,
      history: [],
      set: (patch) => set(patch),
      addRecord: (r) => set((s) => ({ history: [r, ...s.history].slice(0, 40) })),
      clearHistory: () => set({ history: [] }),
    }),
    { name: 'ludo3d.settings', storage: createJSONStorage(() => safeStorage), version: 1 },
  ),
);

export const IS_MOBILE = isMobile;

export function readStorage(key: string): string | null {
  return safeStorage.getItem(key) as string | null;
}

export function writeStorage(key: string, value: string): void {
  safeStorage.setItem(key, value);
}
