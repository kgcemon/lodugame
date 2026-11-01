export type Color = 'red' | 'green' | 'yellow' | 'blue';
export const COLORS: readonly Color[] = ['red', 'green', 'yellow', 'blue'];

export type PlayerKind = 'human' | 'ai';
export type AiLevel = 'easy' | 'medium' | 'hard';

/** Rules that differ between popular Ludo variants. */
export interface RuleConfig {
  /** Dice values that release a token from the yard. Classic: [6]. */
  enterValues: number[];
  /** Rolling a six grants another roll. */
  extraTurnOnSix: boolean;
  /** Rolling this many sixes in a row forfeits the turn (0 disables). */
  maxConsecutiveSixes: number;
  /** Capturing an opponent grants another roll. */
  extraTurnOnCapture: boolean;
  /** Bringing a token home grants another roll. */
  extraTurnOnHome: boolean;
  /** Start cells and star cells protect tokens from capture. */
  safeCells: boolean;
  /** 'first' ends the match when one player finishes; 'all' ranks everyone. */
  playUntil: 'first' | 'all';
}

export interface PlayerState {
  color: Color;
  name: string;
  kind: PlayerKind;
  aiLevel: AiLevel;
  /**
   * Progress of each token relative to this player's start cell:
   *  -1      in the yard
   *  0..50   on the shared track
   *  51..55  on this colour's home column
   *  56      finished
   */
  tokens: number[];
  /** Finishing position (1 = winner), null while still playing. */
  rank: number | null;
}

export type Phase = 'roll' | 'move' | 'over';

export interface GameState {
  config: RuleConfig;
  players: PlayerState[];
  current: number;
  phase: Phase;
  dice: number | null;
  sixStreak: number;
  /** Player indices in finishing order. */
  ranking: number[];
  turnNo: number;
  /** Incremented on every applied action; used to keep clients in sync. */
  seq: number;
}

export type GameAction = { type: 'roll'; value: number } | { type: 'move'; token: number };

export type GameEvent =
  | { type: 'rolled'; player: number; value: number }
  | { type: 'noMoves'; player: number }
  | { type: 'forfeitSixes'; player: number }
  | { type: 'moved'; player: number; token: number; from: number; to: number }
  | { type: 'captured'; player: number; token: number; by: number; from: number }
  | { type: 'tokenHome'; player: number; token: number }
  | { type: 'playerFinished'; player: number; rank: number }
  | { type: 'extraTurn'; player: number; reason: 'six' | 'capture' | 'home' }
  | { type: 'turn'; player: number }
  | { type: 'gameOver'; ranking: number[] };

export interface SeatSetup {
  color: Color;
  name: string;
  kind: PlayerKind;
  aiLevel?: AiLevel;
}
