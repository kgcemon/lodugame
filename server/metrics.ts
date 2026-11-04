import { monitorEventLoopDelay } from 'node:perf_hooks';
import { freemem, loadavg, totalmem } from 'node:os';
import type { Queryable } from './db';

/**
 * What the process is doing, cheap enough to leave on in production: event-loop delay, CPU and
 * memory since the last reading. It warns in the log when the loop falls behind or memory climbs,
 * so a problem leaves a trace without anyone watching. Nothing here runs per request.
 */

const CHECK_MS = 30_000;
/** Above this the players feel it: dice and moves wait this long before anything else happens. */
const LAG_WARN_MS = 200;
const RSS_WARN_MB = 700;
/** Warn at most this often, so a bad hour doesn't fill the disk. */
const WARN_EVERY_MS = 10 * 60_000;

export interface HealthSample {
  uptimeSec: number;
  cpuPercent: number;
  rssMb: number;
  heapUsedMb: number;
  lagMs: { p50: number; p99: number; max: number };
  systemLoad: number[];
  systemFreeMb: number;
  systemTotalMb: number;
}

export class ProcessMetrics {
  private loop = monitorEventLoopDelay({ resolution: 20 });
  private lastCpu = process.cpuUsage();
  private lastAt = process.hrtime.bigint();
  private timer: NodeJS.Timeout | null = null;
  private warnedAt = 0;

  start(): void {
    this.loop.enable();
    this.timer = setInterval(() => this.check(), CHECK_MS);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.loop.disable();
  }

  /** The figures since the previous reading (a reading starts a new window). */
  sample(): HealthSample {
    const cpu = process.cpuUsage(this.lastCpu);
    const now = process.hrtime.bigint();
    const secs = Math.max(0.001, Number(now - this.lastAt) / 1e9);
    this.lastCpu = process.cpuUsage();
    this.lastAt = now;
    const mem = process.memoryUsage();
    const ms = (ns: number) => Math.round(ns / 1e4) / 100;
    const out: HealthSample = {
      uptimeSec: Math.round(process.uptime()),
      // Of one core: 200 is two cores flat out.
      cpuPercent: Math.round(((cpu.user + cpu.system) / 1e6 / secs) * 1000) / 10,
      rssMb: Math.round(mem.rss / 1048576),
      heapUsedMb: Math.round(mem.heapUsed / 1048576),
      // The monitor's timer resolution is counted in its readings: take it off.
      lagMs: {
        p50: Math.max(0, ms(this.loop.percentile(50)) - 20),
        p99: Math.max(0, ms(this.loop.percentile(99)) - 20),
        max: Math.max(0, ms(this.loop.max) - 20),
      },
      systemLoad: loadavg().map((n) => Math.round(n * 100) / 100),
      systemFreeMb: Math.round(freemem() / 1048576),
      systemTotalMb: Math.round(totalmem() / 1048576),
    };
    this.loop.reset();
    return out;
  }

  private check(): void {
    const s = this.sample();
    if (s.lagMs.p99 < LAG_WARN_MS && s.rssMb < RSS_WARN_MB) return;
    const now = Date.now();
    if (now - this.warnedAt < WARN_EVERY_MS) return;
    this.warnedAt = now;
    console.warn(`[health] event loop p99 ${s.lagMs.p99} ms, max ${s.lagMs.max} ms, cpu ${s.cpuPercent}%, rss ${s.rssMb} MB`);
  }
}

/** How long the database takes to answer a trivial question (null: it didn't). */
export async function dbPingMs(db: Queryable): Promise<number | null> {
  const t = process.hrtime.bigint();
  try {
    await db.query('select 1 as ok');
    return Math.round(Number(process.hrtime.bigint() - t) / 1e4) / 100;
  } catch {
    return null;
  }
}
