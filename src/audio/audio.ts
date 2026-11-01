/**
 * Procedural sound design with the Web Audio API — no audio files to load or
 * license. The context is created lazily and resumed on the first user gesture
 * to respect browser autoplay rules.
 */

export type Sfx =
  | 'click'
  | 'diceShake'
  | 'diceHit'
  | 'diceLand'
  | 'move'
  | 'capture'
  | 'home'
  | 'turn'
  | 'extra'
  | 'victory'
  | 'defeat'
  | 'error'
  | 'emote'
  | 'sparkle'
  | 'message';

class AudioEngine {
  private ctx: AudioContext | null = null;
  private master!: GainNode;
  private sfxBus!: GainNode;
  private musicBus!: GainNode;
  private noise!: AudioBuffer;
  private sfxOn = true;
  private sfxVol = 0.8;
  private musicOn = false;
  private musicVol = 0.45;
  private musicTimer: number | null = null;
  private nextChordAt = 0;
  private chordIdx = 0;

  /** Call from a user gesture. Safe to call repeatedly. */
  unlock(): void {
    if (!this.ctx) {
      const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!Ctor) return;
      this.ctx = new Ctor();
      this.master = this.ctx.createGain();
      this.master.gain.value = 0.9;
      const comp = this.ctx.createDynamicsCompressor();
      comp.threshold.value = -14;
      comp.ratio.value = 4;
      this.master.connect(comp).connect(this.ctx.destination);
      this.sfxBus = this.ctx.createGain();
      this.musicBus = this.ctx.createGain();
      this.sfxBus.connect(this.master);
      this.musicBus.connect(this.master);
      this.noise = this.ctx.createBuffer(1, this.ctx.sampleRate, this.ctx.sampleRate);
      const d = this.noise.getChannelData(0);
      for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
      this.applyVolumes();
    }
    if (this.ctx.state === 'suspended') void this.ctx.resume();
    this.updateMusic();
  }

  configure(o: { sound: boolean; soundVolume: number; music: boolean; musicVolume: number }): void {
    this.sfxOn = o.sound;
    this.sfxVol = o.soundVolume;
    this.musicOn = o.music;
    this.musicVol = o.musicVolume;
    if (this.ctx) {
      this.applyVolumes();
      this.updateMusic();
    }
  }

  private applyVolumes(): void {
    const t = this.ctx!.currentTime;
    this.sfxBus.gain.setTargetAtTime(this.sfxOn ? this.sfxVol : 0, t, 0.05);
    this.musicBus.gain.setTargetAtTime(this.musicOn ? this.musicVol * 0.5 : 0, t, 0.3);
  }

  // ------------------------------------------------------------ primitives

  private tone(
    freq: number,
    start: number,
    dur: number,
    opts: { type?: OscillatorType; gain?: number; attack?: number; to?: number; bus?: AudioNode; detune?: number } = {},
  ): void {
    const ctx = this.ctx!;
    const osc = ctx.createOscillator();
    const g = ctx.createGain();
    osc.type = opts.type ?? 'sine';
    osc.frequency.setValueAtTime(freq, start);
    if (opts.detune) osc.detune.value = opts.detune;
    if (opts.to) osc.frequency.exponentialRampToValueAtTime(opts.to, start + dur);
    const peak = opts.gain ?? 0.3;
    const a = opts.attack ?? 0.005;
    g.gain.setValueAtTime(0.0001, start);
    g.gain.exponentialRampToValueAtTime(peak, start + a);
    g.gain.exponentialRampToValueAtTime(0.0001, start + dur);
    osc.connect(g).connect(opts.bus ?? this.sfxBus);
    osc.start(start);
    osc.stop(start + dur + 0.05);
  }

  private noiseBurst(start: number, dur: number, opts: { freq?: number; q?: number; gain?: number; type?: BiquadFilterType } = {}): void {
    const ctx = this.ctx!;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    const f = ctx.createBiquadFilter();
    f.type = opts.type ?? 'bandpass';
    f.frequency.value = opts.freq ?? 2000;
    f.Q.value = opts.q ?? 1.2;
    const g = ctx.createGain();
    g.gain.setValueAtTime(opts.gain ?? 0.3, start);
    g.gain.exponentialRampToValueAtTime(0.0001, start + dur);
    src.connect(f).connect(g).connect(this.sfxBus);
    src.start(start, Math.random() * 0.5, dur + 0.05);
  }

  play(name: Sfx, intensity = 1): void {
    if (!this.ctx || !this.sfxOn || this.ctx.state !== 'running') return;
    const t = this.ctx.currentTime + 0.005;
    const r = (a: number, b: number) => a + Math.random() * (b - a);
    switch (name) {
      case 'click':
        this.tone(r(820, 900), t, 0.07, { type: 'triangle', gain: 0.18, to: 620 });
        break;
      case 'diceShake':
        for (let i = 0; i < 7; i++) {
          this.noiseBurst(t + i * r(0.045, 0.07), 0.04, { freq: r(2500, 4200), q: 3, gain: 0.22 });
        }
        break;
      case 'diceHit':
        this.noiseBurst(t, 0.06, { freq: r(1400, 2200), q: 1.5, gain: 0.35 * intensity });
        this.tone(r(150, 190), t, 0.09, { type: 'sine', gain: 0.35 * intensity, to: 80 });
        break;
      case 'diceLand':
        this.noiseBurst(t, 0.08, { freq: 1200, q: 1, gain: 0.3 });
        this.tone(130, t, 0.16, { type: 'sine', gain: 0.45, to: 70 });
        this.tone(r(1500, 1700), t + 0.03, 0.05, { type: 'triangle', gain: 0.06 });
        break;
      case 'move': {
        const base = r(480, 560);
        this.tone(base, t, 0.09, { type: 'sine', gain: 0.28, to: base * 0.7 });
        this.tone(base * 2.01, t, 0.04, { type: 'triangle', gain: 0.07 });
        this.noiseBurst(t, 0.025, { freq: 3000, q: 2, gain: 0.08 });
        break;
      }
      case 'capture':
        this.tone(700, t, 0.38, { type: 'sawtooth', gain: 0.12, to: 110 });
        this.tone(350, t, 0.3, { type: 'square', gain: 0.06, to: 70 });
        this.noiseBurst(t, 0.35, { freq: 800, q: 0.7, gain: 0.25, type: 'lowpass' });
        this.tone(90, t + 0.02, 0.25, { type: 'sine', gain: 0.4, to: 45 });
        break;
      case 'home':
        [523.25, 659.25, 783.99, 1046.5].forEach((f, i) =>
          this.tone(f, t + i * 0.075, 0.35, { type: 'triangle', gain: 0.16 }),
        );
        this.tone(2093, t + 0.3, 0.5, { type: 'sine', gain: 0.05 });
        break;
      case 'turn':
        this.tone(659.25, t, 0.18, { type: 'sine', gain: 0.12 });
        this.tone(987.77, t + 0.09, 0.25, { type: 'sine', gain: 0.1 });
        break;
      case 'extra':
        this.tone(880, t, 0.12, { type: 'triangle', gain: 0.12 });
        this.tone(1174.66, t + 0.08, 0.2, { type: 'triangle', gain: 0.12 });
        break;
      case 'victory': {
        const notes = [523.25, 659.25, 783.99, 1046.5, 783.99, 1046.5, 1318.5];
        notes.forEach((f, i) => this.tone(f, t + i * 0.11, 0.4, { type: 'triangle', gain: 0.17 }));
        [523.25, 659.25, 783.99, 1046.5].forEach((f) =>
          this.tone(f, t + notes.length * 0.11, 1.6, { type: 'sine', gain: 0.1, attack: 0.03 }),
        );
        break;
      }
      case 'defeat':
        [392, 369.99, 349.23, 293.66].forEach((f, i) =>
          this.tone(f, t + i * 0.2, 0.45, { type: 'triangle', gain: 0.14 }),
        );
        break;
      case 'error':
        this.tone(160, t, 0.15, { type: 'square', gain: 0.07 });
        break;
      case 'emote':
        // Soft bubble pop.
        this.tone(r(380, 440), t, 0.12, { type: 'sine', gain: 0.16, to: 900 });
        this.noiseBurst(t, 0.03, { freq: 2600, q: 2, gain: 0.05 });
        break;
      case 'sparkle':
        [1318.5, 1760, 2093].forEach((f, i) => this.tone(f, t + i * 0.05, 0.25, { type: 'sine', gain: 0.05 }));
        break;
      case 'message':
        this.tone(880, t, 0.09, { type: 'sine', gain: 0.07 });
        this.tone(1318.5, t + 0.06, 0.12, { type: 'sine', gain: 0.06 });
        break;
    }
  }

  // ------------------------------------------------------------ music

  private updateMusic(): void {
    if (!this.ctx) return;
    if (this.musicOn && this.musicTimer === null) {
      this.nextChordAt = this.ctx.currentTime + 0.1;
      this.musicTimer = window.setInterval(() => this.scheduleMusic(), 250);
      this.scheduleMusic();
    } else if (!this.musicOn && this.musicTimer !== null) {
      clearInterval(this.musicTimer);
      this.musicTimer = null;
    }
  }

  /** Gentle lo-fi pad progression with a soft arpeggio, scheduled ahead. */
  private scheduleMusic(): void {
    const ctx = this.ctx!;
    if (ctx.state !== 'running') return;
    const chords = [
      [261.63, 329.63, 392.0, 493.88],
      [220.0, 261.63, 329.63, 392.0],
      [174.61, 220.0, 261.63, 329.63],
      [196.0, 246.94, 293.66, 392.0],
    ];
    const len = 3.2;
    while (this.nextChordAt < ctx.currentTime + 1.2) {
      const t = this.nextChordAt;
      const chord = chords[this.chordIdx % chords.length];
      for (const f of chord) {
        this.tone(f / 2, t, len + 0.6, { type: 'sine', gain: 0.05, attack: 0.9, bus: this.musicBus });
        this.tone(f, t, len + 0.6, { type: 'triangle', gain: 0.018, attack: 1.2, bus: this.musicBus, detune: 6 });
      }
      this.tone(chord[0] / 4, t, len, { type: 'sine', gain: 0.07, attack: 0.3, bus: this.musicBus });
      const pattern = [0, 2, 1, 3, 2, 1, 3, 2];
      pattern.forEach((n, i) => {
        if (Math.random() < 0.8) {
          this.tone(chord[n] * 2, t + i * (len / 8), 0.6, { type: 'sine', gain: 0.035, attack: 0.01, bus: this.musicBus });
        }
      });
      this.chordIdx += 1;
      this.nextChordAt += len;
    }
  }
}

export const audio = new AudioEngine();

export function sfx(name: Sfx, intensity?: number): void {
  audio.play(name, intensity);
}
