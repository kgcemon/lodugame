import { jsonValue, type Db, type Queryable } from './db';
import { HttpError } from './security';

/** Trusted game configuration, stored in game_settings and editable by administrators. */

export interface MatchMode {
  id: string;
  name: string;
  description: string;
  entryFee: number;
  maxPlayers: number;
  matchType: string;
  enabled: boolean;
  /** Only available as a private room (no public matchmaking). */
  privateOnly?: boolean;
}

export interface GameSettings {
  welcomeBonus: number;
  dailyBonus: number;
  /** Winner's total payout = multiplier × entry fee (fee included). */
  payoutMultiplier: number;
  matchmakingWaitSeconds: number;
  suspiciousAmount: number;
  modes: MatchMode[];
}

const KEYS: Record<keyof GameSettings, string> = {
  welcomeBonus: 'welcome_bonus',
  dailyBonus: 'daily_bonus',
  payoutMultiplier: 'payout_multiplier',
  matchmakingWaitSeconds: 'matchmaking_wait_seconds',
  suspiciousAmount: 'suspicious_amount',
  modes: 'match_modes',
};

export function payoutPolicy(s: Pick<GameSettings, 'payoutMultiplier'>): string {
  return (
    `The winner receives ${s.payoutMultiplier}× their own entry fee as the total payout (the fee is included). ` +
    `All players in a match pay the same fee. Entry fees not paid out stay with the house and are recorded ` +
    `per match as the house amount. Bots never receive payouts.`
  );
}

function intIn(v: unknown, name: string, min: number, max: number): number {
  if (typeof v !== 'number' || !Number.isInteger(v) || v < min || v > max) {
    throw new HttpError(400, `${name} must be a whole number between ${min} and ${max}`);
  }
  return v;
}

function validateModes(v: unknown, current: MatchMode[]): MatchMode[] {
  if (!Array.isArray(v)) throw new HttpError(400, 'modes must be a list');
  const byId = new Map(current.map((m) => [m.id, m]));
  const out = v.map((raw) => {
    const m = raw as Partial<MatchMode>;
    const base = typeof m.id === 'string' ? byId.get(m.id) : undefined;
    // Mode ids are referenced by matches and the client, so only existing modes can be edited.
    if (!base) throw new HttpError(400, `Unknown match mode ${String(m.id)}`);
    const text = (x: unknown, name: string, max: number) => {
      if (typeof x !== 'string' || !x.trim() || x.length > max) throw new HttpError(400, `${name} must be 1–${max} characters`);
      return x.trim();
    };
    return {
      ...base,
      name: text(m.name ?? base.name, 'Mode name', 40),
      description: text(m.description ?? base.description, 'Description', 140),
      matchType: text(m.matchType ?? base.matchType, 'Match type', 40),
      entryFee: intIn(m.entryFee ?? base.entryFee, 'Entry fee', base.privateOnly ? 0 : 1, 100_000),
      maxPlayers: intIn(m.maxPlayers ?? base.maxPlayers, 'Max players', 2, 4),
      enabled: typeof m.enabled === 'boolean' ? m.enabled : base.enabled,
    };
  });
  if (new Set(out.map((m) => m.id)).size !== out.length) throw new HttpError(400, 'Duplicate match mode');
  if (out.length !== current.length) throw new HttpError(400, 'Every match mode must be included');
  return out;
}

export class Settings {
  private cache: GameSettings | null = null;
  constructor(private db: Db) {}

  async load(): Promise<GameSettings> {
    const rows = (await this.db.query<{ key: string; value: unknown }>('select "key", value from game_settings')).rows;
    const map = new Map(rows.map((r) => [r.key, jsonValue(r.value)]));
    const get = <K extends keyof GameSettings>(k: K) => map.get(KEYS[k]) as GameSettings[K];
    this.cache = {
      welcomeBonus: Number(get('welcomeBonus')),
      dailyBonus: Number(get('dailyBonus')),
      payoutMultiplier: Number(get('payoutMultiplier')),
      matchmakingWaitSeconds: Number(get('matchmakingWaitSeconds')),
      suspiciousAmount: Number(get('suspiciousAmount')),
      modes: get('modes'),
    };
    return this.cache;
  }

  get(): GameSettings {
    if (!this.cache) throw new Error('Settings not loaded');
    return this.cache;
  }

  mode(id: unknown): MatchMode {
    const m = this.get().modes.find((x) => x.id === id);
    if (!m) throw new HttpError(400, 'Unknown match mode');
    if (!m.enabled) throw new HttpError(400, `${m.name} is currently disabled`);
    return m;
  }

  /** Validate and persist an administrator's changes, with an audit log entry, atomically. */
  async update(patch: Partial<Record<keyof GameSettings, unknown>>, actorId: string): Promise<GameSettings> {
    const cur = this.get();
    const next: GameSettings = { ...cur };
    if (patch.welcomeBonus !== undefined) next.welcomeBonus = intIn(patch.welcomeBonus, 'Welcome bonus', 0, 1_000_000);
    if (patch.dailyBonus !== undefined) next.dailyBonus = intIn(patch.dailyBonus, 'Daily bonus', 0, 100_000);
    if (patch.matchmakingWaitSeconds !== undefined) next.matchmakingWaitSeconds = intIn(patch.matchmakingWaitSeconds, 'Matchmaking wait', 10, 600);
    if (patch.suspiciousAmount !== undefined) next.suspiciousAmount = intIn(patch.suspiciousAmount, 'Suspicious amount', 1, 10_000_000);
    if (patch.payoutMultiplier !== undefined) {
      const m = patch.payoutMultiplier;
      if (typeof m !== 'number' || !Number.isFinite(m) || m < 1 || m > 4 || Math.round(m * 100) !== m * 100) {
        throw new HttpError(400, 'Payout multiplier must be between 1 and 4 (two decimals max)');
      }
      next.payoutMultiplier = m;
    }
    if (patch.modes !== undefined) next.modes = validateModes(patch.modes, cur.modes);

    await this.db.tx(async (q: Queryable) => {
      for (const k of Object.keys(KEYS) as (keyof GameSettings)[]) {
        if (JSON.stringify(next[k]) === JSON.stringify(cur[k])) continue;
        await q.query(
          `insert into game_settings ("key", value, updated_at, updated_by) values ($1, $2::jsonb, now(), $3)
           on conflict ("key") do update set value = excluded.value, updated_at = now(), updated_by = excluded.updated_by`,
          [KEYS[k], JSON.stringify(next[k]), actorId],
        );
      }
      await q.query(`insert into audit_logs (actor_id, action, details) values ($1, 'settings.update', $2::jsonb)`, [
        actorId,
        JSON.stringify({ before: cur, after: next }),
      ]);
    });
    this.cache = next;
    return next;
  }
}
