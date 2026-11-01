/**
 * A tiny frame-driven animation runtime. All scripted animations (dice throws,
 * token hops, camera focus) advance from one useFrame callback with the real
 * frame delta, so they are frame-rate independent and never touch React state
 * per frame.
 */

type Job = (dt: number) => boolean;

const jobs = new Set<Job>();
let generation = 0;

export function tick(dt: number): void {
  // Clamp huge deltas (tab switch) so animations don't teleport mid-flight,
  // but keep real time down to ~10 fps so slow devices don't play in slow motion.
  const d = Math.min(dt, 0.1);
  for (const job of [...jobs]) {
    if (job(d)) jobs.delete(job);
  }
}

/** Cancel every running animation (used when leaving a match). */
export function cancelAll(): void {
  generation += 1;
  jobs.clear();
}

export function currentGeneration(): number {
  return generation;
}

export function tween(seconds: number, update: (t: number) => void): Promise<void> {
  const gen = generation;
  return new Promise((resolve) => {
    if (seconds <= 0) {
      update(1);
      resolve();
      return;
    }
    let elapsed = 0;
    jobs.add((dt) => {
      if (gen !== generation) {
        resolve();
        return true;
      }
      elapsed += dt;
      const t = Math.min(1, elapsed / seconds);
      update(t);
      if (t >= 1) {
        resolve();
        return true;
      }
      return false;
    });
  });
}

export const wait = (seconds: number) => tween(seconds, () => {});

export const ease = {
  linear: (t: number) => t,
  outCubic: (t: number) => 1 - Math.pow(1 - t, 3),
  inOutCubic: (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
  outQuart: (t: number) => 1 - Math.pow(1 - t, 4),
  inOutSine: (t: number) => -(Math.cos(Math.PI * t) - 1) / 2,
  outBack: (t: number) => {
    const c1 = 1.70158;
    const c3 = c1 + 1;
    return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
  },
  outBounce: (t: number) => {
    const n1 = 7.5625;
    const d1 = 2.75;
    if (t < 1 / d1) return n1 * t * t;
    if (t < 2 / d1) return n1 * (t -= 1.5 / d1) * t + 0.75;
    if (t < 2.5 / d1) return n1 * (t -= 2.25 / d1) * t + 0.9375;
    return n1 * (t -= 2.625 / d1) * t + 0.984375;
  },
};

/** Frame-rate independent exponential smoothing factor. */
export function damp(lambda: number, dt: number): number {
  return 1 - Math.exp(-lambda * dt);
}
